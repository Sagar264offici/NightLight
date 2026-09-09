import { canonicalKey, primaryArtistsOf, sameRecording } from '#modules/providers/identity'
import { ITunesCatalogProvider } from '#modules/providers/itunes-catalog-provider'
import { JioSaavnCatalogProvider, MAX_JIOSAAVN_LIMIT, withTimeout } from '#modules/providers/jiosaavn-catalog-provider'
import { JioSaavnPlaybackProvider } from '#modules/providers/jiosaavn-playback-provider'
import { detectVersion, rerankResults } from '#modules/search/services/search-ranker'
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
        return { total: itunesTracks.length, start: 0, results: itunesTracks.map(toUnplayableSong).slice(0, safeLimit) }
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
              artists: primaryArtistsOf(song),
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
        const knownKeys = new Set(pool.tracks.map((s) => canonicalKey(s.name, primaryArtistsOf(s))))
        for (const result of resolutions) {
          if (result.status !== 'fulfilled' || !result.value) continue
          const key = canonicalKey(result.value.name, primaryArtistsOf(result.value))
          if (knownIds.has(result.value.id) || knownKeys.has(key)) continue
          knownIds.add(result.value.id)
          knownKeys.add(key)
          pool.tracks.push(result.value)
          confirmed.add(result.value.id)
        }
      }
    }

    const ranked = rerankResults(
      query,
      pool.tracks,
      (r) => (typeof r.name === 'string' ? r.name : ''),
      primaryArtistsOf,
      (r) => detectVersion(typeof r.name === 'string' ? r.name : ''),
      (r) => (typeof r.playCount === 'number' && Number.isFinite(r.playCount) ? r.playCount : null),
      (r) => (typeof r.id === 'string' ? r.id : undefined),
      undefined,
      (r) => pool.scores.get(r.id) ?? null,
      (r) => (confirmed.has(r.id) ? CANONICAL_CONFIRM_BOOST : 0)
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
        `itunes=${itunesTracks.length} confirmed=${confirmed.size} page=${safePage}`
    )

    return {
      total: pool.total,
      start: pool.start,
      results: ranked.slice(0, safeLimit)
    }
  }
}
