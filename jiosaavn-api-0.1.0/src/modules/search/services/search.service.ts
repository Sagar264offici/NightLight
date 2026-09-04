import {
  GetTrendingUseCase,
  SearchAlbumsUseCase,
  SearchAllUseCase,
  SearchArtistsUseCase,
  SearchPlaylistsUseCase,
  SearchSongsUseCase,
  type SearchAlbumsArgs,
  type SearchArtistsArgs,
  type SearchPlaylistsArgs,
  type SearchSongsArgs
} from '#modules/search/use-cases'
import { GetSongSuggestionsUseCase } from '#modules/songs/use-cases'

interface RadioSongsArgs {
  seedId?: string
  seedName?: string
  artists?: string
  album?: string
  limit?: number
}

/** Exact song shape produced by the search use case (SongModel). */
type SongResult = Awaited<ReturnType<SearchSongsUseCase['execute']>>['results'][number]

/** Lowercases and strips remix/cover/acoustic/live style suffixes. */
function canonicalTitle(raw: unknown): string {
  if (typeof raw !== 'string') return ''
  return raw
    .toLowerCase()
    .replace(/\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/[-–—:;./_+]/g, ' ')
    .replace(/\b(feat|ft|from|official|lyrics|video|version|edit|extended|remix|acoustic|live|slowed|sped up|cover|instrumental|karaoke|reprise)\b.*$/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export class SearchService {
  private readonly searchAllUseCase: SearchAllUseCase
  private readonly searchSongsUseCase: SearchSongsUseCase
  private readonly searchAlbumsUseCase: SearchAlbumsUseCase
  private readonly searchArtistsUseCase: SearchArtistsUseCase
  private readonly searchPlaylistsUseCase: SearchPlaylistsUseCase
  private readonly songSuggestions: GetSongSuggestionsUseCase
  private readonly trendingUseCase: GetTrendingUseCase

  constructor() {
    this.searchAllUseCase = new SearchAllUseCase()
    this.searchSongsUseCase = new SearchSongsUseCase()
    this.searchAlbumsUseCase = new SearchAlbumsUseCase()
    this.searchArtistsUseCase = new SearchArtistsUseCase()
    this.searchPlaylistsUseCase = new SearchPlaylistsUseCase()
    this.songSuggestions = new GetSongSuggestionsUseCase()
    this.trendingUseCase = new GetTrendingUseCase()
  }

  searchAll = (query: string) => {
    return this.searchAllUseCase.execute(query)
  }

  searchSongs = (args: SearchSongsArgs) => {
    return this.searchSongsUseCase.execute(args)
  }

  /**
   * "Radio" seeding: given the track currently playing, find OTHER songs that
   * feel related. JioSaavn's own suggestion engine is preferred (it returns
   * genuinely similar music); the artist + album search is the fallback. No
   * two queued songs ever share the same title, and the seed never repeats,
   * so auto-next and shuffle keep delivering fresh songs of the same vibe.
   */
  radioSongs = async ({ seedId, seedName, artists, album, limit = 30 }: RadioSongsArgs) => {
    const wanted = Math.min(Math.max(limit, 1), 50)
    const results: SongResult[] = []

    if (seedId) {
      try {
        const suggestions = await this.songSuggestions.execute({ songId: seedId, limit: Math.min(wanted + 10, 50) })
        if (suggestions?.length) {
          results.push(...suggestions)
        }
      } catch {
        // JioSaavn suggestions unavailable for this seed; fall back below.
      }
    }

    if (results.length < wanted) {
      const primary = (artists ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)[0]
      const queries: string[] = []
      if (primary) {
        queries.push(primary)
      } else if (seedName) {
        queries.push(seedName)
      }
      const albumName = album && album.trim() ? album.trim() : ''
      if (albumName && albumName.toLowerCase() !== (primary || '').toLowerCase()) {
        queries.push(albumName)
      }
      for (const q of queries) {
        try {
          const page = await this.searchSongsUseCase.execute({ query: q, page: 0, limit: 30 })
          if (page?.results) {
            results.push(...page.results)
          }
        } catch {
          // One failing seed must not sink the whole radio response.
        }
      }
    }

    const out: SongResult[] = []
    const seenIds = new Set<string>()
    const seenTitles = new Set<string>()
    // Never surface the seed itself again under another recording id.
    const seedCanon = canonicalTitle(seedName)
    if (seedCanon) {
      seenTitles.add(seedCanon)
    }
    for (const song of results) {
      if (!song || typeof song.id !== 'string') continue
      if (song.id === seedId) continue
      if (seenIds.has(song.id)) continue
      const title = canonicalTitle(song.name)
      if (!title || seenTitles.has(title)) continue // same title = same song feel
      seenIds.add(song.id)
      seenTitles.add(title)
      out.push(song)
      if (out.length >= wanted) break
    }

    return { total: out.length, start: 0, results: out }
  }

  searchAlbums = (args: SearchAlbumsArgs) => {
    return this.searchAlbumsUseCase.execute(args)
  }

  searchArtists = (args: SearchArtistsArgs) => {
    return this.searchArtistsUseCase.execute(args)
  }

  searchPlaylists = (args: SearchPlaylistsArgs) => {
    return this.searchPlaylistsUseCase.execute(args)
  }

  trendingSongs = () => {
    return this.trendingUseCase.execute()
  }
}
