import { canonicalKey, performerArtistsOf, sameRecording } from '#modules/providers/identity'
import { ITunesCatalogProvider } from '#modules/providers/itunes-catalog-provider'
import { JioSaavnCatalogProvider, MAX_JIOSAAVN_LIMIT, withTimeout } from '#modules/providers/jiosaavn-catalog-provider'
import { JioSaavnPlaybackProvider } from '#modules/providers/jiosaavn-playback-provider'
import {
  detectVersion,
  extractIntent,
  normaliseTitle,
  rerankResults,
  scoreCandidate
} from '#modules/search/services/search-ranker'
import { PopularityService } from '#modules/stats/services/popularity.service'
import { HTTPException } from 'hono/http-exception'
import type { IUseCase } from '#common/types'
import type { CatalogTrack, SongPayload } from '#modules/providers/music-providers'
import type { SearchSongModel } from '#modules/search/models'
import type { z } from 'zod'

export interface SearchSongsArgs {
  query: string
  page: number
  limit: number
  /** Opt-in diagnostics (candidate pool + per-candidate scores). Never on by default. */
  debug?: boolean
}

export interface SearchDebugCandidate {
  id: string
  title: string
  artist: string
  version: string
  language: string
  playCount: number | null
  providerScore: number | null
  confirmed: boolean
  canonicalArtist: boolean
  upstream: { source: string; rank: number } | null
  finalScore: number
}

export interface SearchDebug {
  query: string
  via: string
  directCount: number
  enrichedCount: number
  itunesCount: number
  fusedCount: number
  canonicalArtists: string[]
  candidates: SearchDebugCandidate[]
}

/**
 * Hybrid song search (approved architecture §3):
 *
 *   query → JioSaavn retrieval ─┐
 *                               ├→ cross-provider identity match
 *         iTunes retrieval ─────┘         ↓
 *                              NightLight ranking → results
 *
 * Bounded fanout: JioSaavn pool (≤50) + iTunes (≤10, 6s budget) +
 * at most 3 metadata resolutions (8s shared budget). iTunes never fails
 * the request; total JioSaavn failure falls back to iTunes metadata rows;
 * both failing yields a clean SEARCH_UNAVAILABLE error.
 */
const ITUNES_HITS = 10
const ITUNES_BUDGET_MS = 6000
const RESOLVE_BUDGET_MS = 5000
const MAX_RESOLVES = 3
const CANONICAL_CONFIRM_BOOST = 2
/** Canonical-artist preference for explicit variant queries (bounded, evidence-based). */
const CANONICAL_ARTIST_BOOST = 3

function durationMsOf(song: SongPayload): number {
  return typeof song.duration === 'number' ? song.duration * 1000 : 0
}

/** Structurally valid but unplayable SongModel for provider-outage fallback. */
function toUnplayableSong(hit: CatalogTrack): SongPayload {
  return {
    id: `itunes:${hit.providerId}`,
    name: hit.title,
    type: 'song',
    year: hit.year || null,
    releaseDate: null,
    duration: hit.durationMs > 0 ? Math.round(hit.durationMs / 1000) : null,
    label: null,
    explicitContent: hit.explicit,
    playCount: null,
    language: '',
    hasLyrics: false,
    lyricsId: null,
    url: '',
    copyright: null,
    album: { id: null, name: hit.album || null, url: null },
    artists: {
      primary: hit.artists.map((name) => ({ id: '', name, role: '', image: [], type: '', url: '' })),
      featured: [],
      all: []
    },
    image: hit.artwork ? [{ quality: '500x500', url: hit.artwork }] : [],
    downloadUrl: []
  }
}

export class SearchSongsUseCase implements IUseCase<SearchSongsArgs, z.infer<typeof SearchSongModel>> {
  private readonly jioSaavn = new JioSaavnCatalogProvider()
  private readonly itunes = new ITunesCatalogProvider()
  private readonly playback = new JioSaavnPlaybackProvider()
  private readonly popularity = new PopularityService()

  constructor() {}

  async execute({ query, limit, page }: SearchSongsArgs): Promise<z.infer<typeof SearchSongModel>> {
    const { payload } = await this.run({ query, limit, page, debug: false })
    return payload
  }

  /**
   * Same pipeline with an opt-in diagnostic trace: per-candidate origin,
   * upstream rank and final score. Powers `?debug=true` on the endpoint.
   */
  async searchWithTrace(
    args: SearchSongsArgs
  ): Promise<{ payload: z.infer<typeof SearchSongModel>; trace: SearchDebug }> {
    const { payload, trace } = await this.run({ ...args, debug: true })
    return { payload, trace: trace as SearchDebug }
  }

  private async run(args: SearchSongsArgs & { debug: boolean }): Promise<{
    payload: z.infer<typeof SearchSongModel>
    trace?: SearchDebug
  }> {
    const { query, limit, page, debug } = args
    const safeLimit = Math.min(Math.max(Math.floor(limit) || 10, 1), MAX_JIOSAAVN_LIMIT)
    const safePage = Math.max(Math.floor(page) || 0, 0)
    const startedAt = Date.now()

    const [jioSettled, itunesSettled] = await Promise.allSettled([
      this.jioSaavn.searchPool(query, safeLimit, safePage),
      safePage === 0
        ? withTimeout(this.itunes.searchTracks(query, { limit: ITUNES_HITS }), ITUNES_BUDGET_MS)
        : Promise.resolve([] as CatalogTrack[])
    ])
    const itunesTracks = itunesSettled.status === 'fulfilled' ? itunesSettled.value : []

    if (jioSettled.status === 'rejected') {
      // §21: JioSaavn down, iTunes alive → metadata rows (unplayable) beat an empty screen.
      if (itunesTracks.length > 0) {
        return {
          payload: {
            total: itunesTracks.length,
            start: 0,
            results: itunesTracks.map(toUnplayableSong).slice(0, safeLimit)
          }
        }
      }
      throw new HTTPException(502, { message: 'Search is temporarily unavailable' })
    }

    const pool = jioSettled.value
    const confirmed = new Set<string>()
    const usedItunes = new Set<string>()

    // Cross-provider identity: iTunes independently confirming the same
    // recording earns a small canonical boost (never dominant).
    for (const song of pool.tracks) {
      const songVersion = detectVersion(song.name)
      const hit = itunesTracks.find(
        (candidate) =>
          !usedItunes.has(candidate.providerId) &&
          sameRecording(
            {
              title: song.name,
              artists: performerArtistsOf(song),
              version: songVersion,
              album: song.album?.name ?? '',
              durationMs: durationMsOf(song)
            },
            {
              title: candidate.title,
              artists: candidate.artists,
              version: detectVersion(candidate.title),
              album: candidate.album,
              durationMs: candidate.durationMs
            }
          )
      )
      if (hit) {
        confirmed.add(song.id)
        usedItunes.add(hit.providerId)
      }
    }

    // Resolve top unmatched iTunes hits to playable JioSaavn tracks (§9):
    // canonical metadata in, ranked JioSaavn equivalent out — never blind first-pick.
    if (safePage === 0) {
      const unmatched = itunesTracks.filter((hit) => !usedItunes.has(hit.providerId)).slice(0, MAX_RESOLVES)
      if (unmatched.length > 0) {
        const resolutions = await withTimeout(
          Promise.allSettled(
            unmatched.map((hit) =>
              this.playback.resolveByMetadata({
                title: hit.title,
                artists: hit.artists,
                album: hit.album,
                version: detectVersion(hit.title)
              })
            )
          ),
          RESOLVE_BUDGET_MS
        ).catch(() => [] as PromiseSettledResult<SongPayload | null>[])
        const knownIds = new Set(pool.tracks.map((s) => s.id))
        const knownKeys = new Set(pool.tracks.map((s) => canonicalKey(s.name, performerArtistsOf(s))))
        for (const result of resolutions) {
          if (result.status !== 'fulfilled' || !result.value) continue
          const key = canonicalKey(result.value.name, performerArtistsOf(result.value))
          if (knownIds.has(result.value.id) || knownKeys.has(key)) continue
          knownIds.add(result.value.id)
          knownKeys.add(key)
          pool.tracks.push(result.value)
          confirmed.add(result.value.id)
        }
      }
    }

    // Canonical artists: performers holding an exact-title ORIGINAL in the
    // pool. Pure pool evidence — the original recording defines whose
    // versions are canonical for this title. Applied only when the query
    // explicitly requests a version (plain queries already prefer originals).
    const intent = extractIntent(query)
    const canonicalArtists = new Set<string>()
    if (intent.variant && intent.variant !== 'original' && intent.title) {
      const wantTitle = normaliseTitle(intent.title)
      for (const song of pool.tracks) {
        if (detectVersion(song.name) === 'original' && normaliseTitle(song.name) === wantTitle) {
          for (const artist of performerArtistsOf(song)) canonicalArtists.add(artist.toLowerCase())
        }
      }
    }
    const isCanonicalArtist = (song: SongPayload): boolean =>
      performerArtistsOf(song).some((a) => canonicalArtists.has(a.toLowerCase()))

    const ranked = rerankResults(
      query,
      pool.tracks,
      (r) => (typeof r.name === 'string' ? r.name : ''),
      performerArtistsOf,
      (r) => detectVersion(typeof r.name === 'string' ? r.name : ''),
      (r) => (typeof r.playCount === 'number' && Number.isFinite(r.playCount) ? r.playCount : null),
      (r) => (typeof r.id === 'string' ? r.id : undefined),
      undefined,
      (r) => pool.scores.get(r.id) ?? null,
      (r) => (confirmed.has(r.id) ? CANONICAL_CONFIRM_BOOST : 0),
      (r) => (isCanonicalArtist(r) ? CANONICAL_ARTIST_BOOST : 0)
    )

    if (safePage === 0) {
      // Anonymous search event for most-searched/velocity. Fire-and-forget:
      // analytics must never slow or break search.
      this.popularity.recordSearch(query).then(
        () => {},
        () => {}
      )
    }

    const latencyMs = Date.now() - startedAt
    console.info(
      `[search] rung=jiosaavn(${pool.via})+itunes latencyMs=${latencyMs} pool=${pool.tracks.length} ` +
        `itunes=${itunesTracks.length} confirmed=${confirmed.size} canonicalArtists=${canonicalArtists.size} page=${safePage}`
    )

    const payload = {
      total: pool.total,
      start: pool.start,
      results: ranked.slice(0, safeLimit)
    }

    if (!debug) return { payload }

    const trace: SearchDebug = {
      query,
      via: pool.via,
      directCount: [...pool.ranks.values()].filter((r) => r.source === 'primary').length,
      enrichedCount: [...pool.ranks.values()].filter((r) => r.source === 'enriched').length,
      itunesCount: itunesTracks.length,
      fusedCount: pool.tracks.length,
      canonicalArtists: [...canonicalArtists],
      candidates: pool.tracks.map((song) => {
        const artists = performerArtistsOf(song)
        const version = detectVersion(song.name)
        const playCount = typeof song.playCount === 'number' && Number.isFinite(song.playCount) ? song.playCount : null
        const providerScore = pool.scores.get(song.id) ?? null
        const rank = pool.ranks.get(song.id) ?? null
        return {
          id: song.id,
          title: song.name,
          artist: artists[0] ?? '',
          version,
          language: song.language ?? '',
          playCount,
          providerScore,
          confirmed: confirmed.has(song.id),
          canonicalArtist: isCanonicalArtist(song),
          upstream: rank ? { source: rank.source, rank: rank.rank } : null,
          finalScore: scoreCandidate(
            intent,
            song.name,
            artists,
            version,
            playCount,
            0,
            providerScore,
            confirmed.has(song.id) ? CANONICAL_CONFIRM_BOOST : 0,
            isCanonicalArtist(song) ? CANONICAL_ARTIST_BOOST : 0
          )
        }
      })
    }
    trace.candidates.sort((a, b) => b.finalScore - a.finalScore)

    return { payload, trace }
  }
}
