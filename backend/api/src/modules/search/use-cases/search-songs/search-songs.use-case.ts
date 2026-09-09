import { Endpoints } from '#common/constants'
import { useFetch } from '#common/helpers'
import { detectVersion, rerankResults } from '#modules/search/services/search-ranker'
import { createSongPayload } from '#modules/songs/helpers'
import { GetSongByIdUseCase } from '#modules/songs/use-cases'
import type { IUseCase } from '#common/types'
import type { SearchSongAPIResponseModel, SearchSongModel } from '#modules/search/models'
import type { SongModel } from '#modules/songs/models'
import type { z } from 'zod'

export interface SearchSongsArgs {
  query: string
  page: number
  limit: number
}

/** Minimal autocomplete entry (only fields the fusion needs). */
interface AutocompleteEntry {
  id?: string
  title?: string
  type?: string
  more_info?: {
    score?: string
    ctr?: number
  }
}

interface AutocompleteResponse {
  songs?: { data?: AutocompleteEntry[] }
  topquery?: { data?: AutocompleteEntry[] }
}

type SongPayload = z.infer<typeof SongModel>

/** Upstream page-size cap (also bounds client `limit`). */
const MAX_LIMIT = 50
/** Autocomplete song hits resolved per query. */
const AUTOCOMPLETE_HITS = 10
/** Enrichment budget: search must never get slower than this on top of the primary rung. */
const ENRICH_TIMEOUT_MS = 6000

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error('enrichment timeout')), ms)
  })
  return Promise.race([promise, timeout]).finally(() => {
    if (timer) clearTimeout(timer)
  })
}

/** Provider relevance first (`score`), click-through fallback (`ctr`), else 0. */
function hitScore(entry: AutocompleteEntry): number {
  const score = Number(entry.more_info?.score)
  if (Number.isFinite(score) && score > 0) return score
  const ctr = Number(entry.more_info?.ctr)
  if (Number.isFinite(ctr) && ctr > 0) return ctr
  return 0
}

const primaryArtistsOf = (song: SongPayload): string[] => {
  const primary = song.artists?.primary
  if (Array.isArray(primary)) return primary.map((a) => a?.name ?? '').filter(Boolean)
  return []
}

/**
 * Canonical identity for cross-pool dedupe: upstream hands the same
 * recording different ids across endpoints (mini vs full objects), so id
 * matching alone leaves visible duplicates.
 */
const canonicalKey = (name: string, artists: string[]): string => {
  const title = (name ?? '')
    .toLowerCase()
    .replaceAll(/\([^)]*\)/g, ' ')
    .replaceAll(/\[[^\]]*\]/g, ' ')
    .replaceAll(/[^a-z0-9\s]/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
  const artist = artists
    .map((a) => (a ?? '').toLowerCase().trim())
    .filter(Boolean)
    .sort()
    .join(',')
  return `${title}|||${artist}`
}

export class SearchSongsUseCase implements IUseCase<SearchSongsArgs, z.infer<typeof SearchSongModel>> {
  private readonly songById = new GetSongByIdUseCase()

  constructor() {}

  async execute({ query, limit, page }: SearchSongsArgs): Promise<z.infer<typeof SearchSongModel>> {
    const safeLimit = Math.min(Math.max(Math.floor(limit) || 10, 1), MAX_LIMIT)
    const safePage = Math.max(Math.floor(page) || 0, 0)

    // Primary rung: classic catalog search (always required — never fails silently).
    const { data } = await useFetch<z.infer<typeof SearchSongAPIResponseModel>>({
      endpoint: Endpoints.search.songs,
      params: {
        q: query,
        p: safePage,
        n: safeLimit
      }
    })
    const primary: SongPayload[] = []
    for (const song of data.results?.map(createSongPayload) || []) {
      if (primary.length >= safeLimit) break
      primary.push(song)
    }

    // Enrichment rung (first page only): autocomplete surfaces globally
    // relevant hits — with a provider `score` — that the text pool can miss
    // entirely on region-skewed indexes (e.g. Ed Sheeran's "Perfect" for
    // query "perfect"). Hits are resolved to full songs so they stay
    // playable and carry playCount. Must never fail or stall the request.
    let providerScores = new Map<string, number>()
    let enriched: SongPayload[] = []
    if (safePage === 0) {
      try {
        const found = await withTimeout(this.fetchAutocompleteSongs(query), ENRICH_TIMEOUT_MS)
        enriched = found.songs
        providerScores = found.scores
      } catch {
        enriched = []
        providerScores = new Map()
      }
    }

    // Merge with canonical dedupe: upstream hands the same recording
    // different ids within AND across endpoints (mini vs full objects), so
    // id matching alone leaves visible duplicates. First occurrence wins.
    const seenIds = new Set<string>()
    const seenKeys = new Set<string>()
    const pool: SongPayload[] = []
    const consider = (song: SongPayload) => {
      const key = canonicalKey(song.name, primaryArtistsOf(song))
      if (seenIds.has(song.id) || seenKeys.has(key)) return
      seenIds.add(song.id)
      seenKeys.add(key)
      pool.push(song)
    }
    for (const song of primary) consider(song)
    for (const song of enriched) consider(song)

    // Single ranking authority: intent + version + playCount + provider score.
    const ranked = rerankResults(
      query,
      pool,
      (r) => (typeof r.name === 'string' ? r.name : ''),
      primaryArtistsOf,
      (r) => detectVersion(typeof r.name === 'string' ? r.name : ''),
      (r) => (typeof r.playCount === 'number' && Number.isFinite(r.playCount) ? r.playCount : null),
      (r) => (typeof r.id === 'string' ? r.id : undefined),
      undefined,
      (r) => providerScores.get(r.id) ?? null
    )

    return {
      total: data.total,
      start: data.start,
      results: ranked.slice(0, safeLimit)
    }
  }

  /**
   * Resolves autocomplete song hits to full playable payloads.
   * Returns empty on any failure (miss, 404, timeout) — callers treat
   * enrichment as strictly optional.
   */
  private async fetchAutocompleteSongs(query: string): Promise<{ songs: SongPayload[]; scores: Map<string, number> }> {
    const empty = { songs: [], scores: new Map<string, number>() }
    const { data, ok } = await useFetch<AutocompleteResponse>({
      endpoint: Endpoints.search.all,
      params: { query }
    })
    if (!ok || !data) return empty

    const hits: { id: string; score: number }[] = []
    const seen = new Set<string>()
    const collect = (entries?: AutocompleteEntry[]) => {
      for (const entry of entries ?? []) {
        if (entry?.type !== 'song' || !entry.id || seen.has(entry.id)) continue
        seen.add(entry.id)
        hits.push({ id: entry.id, score: hitScore(entry) })
        if (hits.length >= AUTOCOMPLETE_HITS) break
      }
    }
    // topquery first: the provider's single best guess for the query.
    collect(data.topquery?.data)
    collect(data.songs?.data)
    if (hits.length === 0) return empty

    const scores = new Map(hits.map((h) => [h.id, h.score] as [string, number]))
    const songs = await this.songById.execute({ songIds: hits.map((h) => h.id).join(',') })
    return { songs, scores }
  }
}
