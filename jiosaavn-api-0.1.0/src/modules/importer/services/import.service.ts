import { SearchSongsUseCase } from '#modules/search/use-cases'
import { GetPlaylistByLinkUseCase } from '#modules/playlists/use-cases/get-playlist-by-link'

interface PlaylistTrack {
  title: string
  artist?: string
}

interface ImportedPlaylist {
  source: string
  playlistName: string
  totalTracks: number
  matched: number
  unmatched: string[]
  results: Record<string, unknown>[]
}

/**
 * Resolves public playlist URLs (Spotify / YouTube) and matches each track to
 * a playable song in the music library. No API keys: Spotify's public embed
 * payload and YouTube's own web-client data (INNERTUBE) are used.
 */
export class ImportService {
  private readonly searchSongsUseCase = new SearchSongsUseCase()

  async importPlaylist(rawUrl: string, limit = 60): Promise<ImportedPlaylist> {
    const url = new URL(rawUrl)
    const host = url.hostname.replace(/^www\./, '').replace(/^music\./, '').toLowerCase()
    const wanted = Math.min(Math.max(limit, 1), 100)

    let items: PlaylistTrack[] = []
    let name = ''
    let source = ''

    if (host.includes('spotify.com')) {
      source = 'spotify'
      const parsed = await this.fetchSpotify(url)
      items = parsed.items
      name = parsed.name
    } else if (host.includes('youtube.com') || host.includes('youtu.be')) {
      source = 'youtube'
      const parsed = await this.fetchYouTube(url)
      items = parsed.items
      name = parsed.name
    } else if (host.includes('apple.com')) {
      source = 'apple-music'
      const parsed = await this.fetchApple(url)
      items = parsed.items
      name = parsed.name
    } else if (host.includes('jiosaavn.com') || host.includes('saavn.com')) {
      // JioSaavn playlists are already playable library songs — no re-matching.
      source = 'jiosaavn'
      const parsed = await this.fetchJioSaavn(url, wanted)
      return {
        source,
        playlistName: parsed.name,
        totalTracks: parsed.items.length,
        matched: parsed.items.length,
        unmatched: [],
        results: parsed.items
      }
    } else {
      throw new Error('Only Spotify, YouTube, Apple Music and JioSaavn playlist links are supported')
    }

    if (items.length === 0) {
      throw new Error('No public tracks found — the playlist may be private or unavailable')
    }
    if (items.length > wanted) {
      items = items.slice(0, wanted)
    }

    // Match each source track to a playable library song.
    const results: Record<string, unknown>[] = []
    const unmatched: string[] = []
    const seen = new Set<string>()

    for (const item of items) {
      const best = await this.bestMatch(item.title, item.artist)
      if (best && !seen.has(String((best as { id: string }).id))) {
        seen.add(String((best as { id: string }).id))
        results.push(best as Record<string, unknown>)
      } else {
        const label = item.title.trim().slice(0, 120)
        if (!unmatched.includes(label)) {
          unmatched.push(label)
        }
      }
    }

    return {
      source,
      playlistName: name || `${source} import`,
      totalTracks: items.length,
      matched: results.length,
      unmatched,
      results
    }
  }

  // ---- Spotify ----

  private async fetchSpotify(url: URL): Promise<{ items: PlaylistTrack[]; name: string }> {
    const id = url.pathname.match(/playlist\/([A-Za-z0-9]+)/)?.[1]
    if (!id) {
      throw new Error('Not a Spotify playlist link')
    }
    const html = await this.httpGet(`https://open.spotify.com/embed/playlist/${id}`)
    const m = html.match(/<script id="__NEXT_DATA__" type="application\/json"[^>]*>(.*?)<\/script>/s)
    if (!m) {
      throw new Error('Spotify did not return playlist data')
    }
    const data = JSON.parse(m[1])
    const entity = data?.props?.pageProps?.state?.data?.entity
    const trackList = Array.isArray(entity?.trackList) ? entity.trackList : []
    const items: PlaylistTrack[] = []
    for (const t of trackList) {
      if (t && typeof t.title === 'string' && t.isPlayable !== false) {
        items.push({ title: t.title, artist: typeof t.subtitle === 'string' ? t.subtitle : undefined })
      }
    }
    return { items, name: typeof entity?.name === 'string' ? entity.name : 'Spotify playlist' }
  }

  // ---- YouTube ----

  private async fetchYouTube(url: URL): Promise<{ items: PlaylistTrack[]; name: string }> {
    const list = url.searchParams.get('list')
    if (!list) {
      throw new Error('Not a YouTube playlist link')
    }
    // 1) Standard playlist HTML: parse the initial items directly.
    const html = await this.httpGet(`https://www.youtube.com/playlist?list=${encodeURIComponent(list)}`)
    const key = html.match(/"INNERTUBE_API_KEY":"([^"]+)"/)?.[1]
    const initial = this.parseYtData(html.match(/var ytInitialData = ({.*?});<\/script>/s)?.[1])
    if (!initial) {
      throw new Error('YouTube did not return playlist data')
    }
    const items = this.collectYtItems(initial)
    const meta = (initial as {
      metadata?: { playlistMetadataRenderer?: { title?: string } }
    })?.metadata?.playlistMetadataRenderer?.title
    const name = meta ?? this.parseTitle(html) ?? 'YouTube playlist'

    // 2) If the first page was lazy, page through YouTube's web client.
    if (items.length === 0 && key) {
      const body = {
        context: { client: { clientName: 'WEB', clientVersion: '2.20241001.00.00', hl: 'en' } },
        browseId: `VL${list}`,
        racyCheckOk: true
      }
      try {
        const first = await this.httpJson('https://www.youtube.com/youtubei/v1/browse', key, body)
        let page = first
        for (let hop = 0; hop < 6 && items.length < 100; hop++) {
          const batch = this.collectYtItems(page)
          items.push(...batch)
          const token = this.findContinuation(page)
          if (!token) break
          page = await this.httpJson('https://www.youtube.com/youtubei/v1/browse', key, {
            context: { client: { clientName: 'WEB', clientVersion: '2.20241001.00.00', hl: 'en' } },
            continuation: token
          })
        }
      } catch {
        // Fall through with whatever the HTML page gave us.
      }
    }
    return { items, name: String(name) }
  }

  private parseYtData(raw?: string): unknown {
    if (!raw) return null
    try {
      return JSON.parse(raw)
    } catch {
      return null
    }
  }

  private collectYtItems(o: unknown): PlaylistTrack[] {
    const out: PlaylistTrack[] = []
    const walk = (node: unknown): void => {
      if (Array.isArray(node)) {
        for (const n of node) walk(n)
        return
      }
      if (!node || typeof node !== 'object') return
      const rec = node as Record<string, unknown>
      const pvr = rec.playlistVideoRenderer as Record<string, unknown> | undefined
      if (pvr && typeof pvr.videoId === 'string') {
        const runs = ((pvr.title as Record<string, unknown>)?.runs as { text?: string }[]) ?? []
        const title = runs.map((r) => r.text ?? '').join('')
        const by = ((pvr.shortBylineText as Record<string, unknown>)?.runs as { text?: string }[]) ?? []
        const channel = by.map((r) => r.text ?? '').join('')
        if (title) out.push({ title, artist: channel })
      }
      for (const v of Object.values(rec)) walk(v)
    }
    walk(o)
    return out
  }

  private findContinuation(o: unknown): string | null {
    if (Array.isArray(o)) {
      for (const n of o) {
        const t = this.findContinuation(n)
        if (t) return t
      }
      return null
    }
    if (!o || typeof o !== 'object') return null
    const rec = o as Record<string, unknown>
    const cmd = rec.continuationCommand as Record<string, unknown> | undefined
    if (cmd && typeof cmd.token === 'string') return cmd.token
    for (const v of Object.values(rec)) {
      const t = this.findContinuation(v)
      if (t) return t
    }
    return null
  }

  private parseTitle(html: string): string {
    const m = html.match(/<title>(.*?)<\/title>/s)
    if (!m) return 'YouTube playlist'
    return m[1].replace(/\s*-\s*YouTube\s*$/i, '').trim().slice(0, 200)
  }

  // ---- Apple Music ----

  private async fetchApple(url: URL): Promise<{ items: PlaylistTrack[]; name: string }> {
    const html = await this.httpGet(url.toString())
    // Apple pages ship schema.org JSON-LD (MusicPlaylist + track list).
    const blocks = html.matchAll(/<script type="application\/ld\+json">(.*?)<\/script>/gs)
    for (const b of blocks) {
      try {
        const doc = JSON.parse(b[1].trim())
        const playlist = Array.isArray(doc) ? doc.find((x) => x?.['@type'] === 'MusicPlaylist') : doc
        if (!playlist || playlist['@type'] !== 'MusicPlaylist') continue
        const rawTracks = (Array.isArray(playlist.track) ? playlist.track : []) as {
          name?: unknown
          byArtist?: unknown
        }[]
        const items: PlaylistTrack[] = []
        for (const t of rawTracks) {
          if (typeof t?.name !== 'string' || !t.name.trim()) continue
          let artist: unknown
          if (Array.isArray(t.byArtist)) {
            artist = (t.byArtist[0] as { name?: unknown } | undefined)?.name
          } else {
            artist = (t.byArtist as { name?: unknown } | undefined)?.name
          }
          items.push({ title: t.name, artist: typeof artist === 'string' ? artist : undefined })
        }
        if (items.length) {
          return { items, name: String(playlist.name ?? 'Apple Music playlist') }
        }
      } catch {
        // Try the next JSON-LD block.
      }
    }
    throw new Error('Could not read this Apple Music playlist (it may be private or regional)')
  }

  // ---- JioSaavn ----

  private async fetchJioSaavn(url: URL, wanted: number): Promise<{ items: Record<string, unknown>[]; name: string }> {
    const token = url.pathname.split('/').filter(Boolean).pop()
    if (!token) {
      throw new Error('Not a JioSaavn playlist link')
    }
    const useCase = new GetPlaylistByLinkUseCase()
    const items: Record<string, unknown>[] = []
    let name = 'JioSaavn playlist'
    for (let page = 0; page < 4 && items.length < wanted; page++) {
      const batch = await useCase.execute({ token, limit: Math.min(wanted, 50), page })
      const songs = (batch?.songs ?? []) as Record<string, unknown>[]
      if (batch?.name) name = String(batch.name)
      if (songs.length === 0) break
      items.push(...songs)
    }
    if (items.length === 0) {
      throw new Error('JioSaavn returned no songs for this playlist')
    }
    return { items: items.slice(0, wanted), name }
  }

  // ---- Matching ----

  private async bestMatch(title: string, artist?: string): Promise<unknown | null> {
    const query = [title, artist].filter((s) => s && s.trim()).join(' ').trim().slice(0, 120)
    if (!query) return null
    try {
      const res = await this.searchSongsUseCase.execute({ query, page: 0, limit: 8 })
      const songs = res?.results ?? []
      if (songs.length === 0) return null

      const wantTitle = canonical(title)
      const wantArtist = canonical(artist ?? '')
      let best: unknown | null = null
      let bestScore = 0
      for (const s of songs) {
        const song = s as {
          name?: string
          artists?: { primary?: { name?: string }[] }
        }
        const gotTitle = canonical(song.name ?? '')
        const gotArtist = canonical(song.artists?.primary?.[0]?.name ?? '')
        let score = 0
        if (wantTitle && gotTitle) {
          if (gotTitle === wantTitle) score += 3
          else if (gotTitle.includes(wantTitle) || wantTitle.includes(gotTitle)) score += 2
        }
        if (wantArtist && gotArtist) {
          if (gotArtist === wantArtist) score += 2
          else if (gotArtist.includes(wantArtist) || wantArtist.includes(gotArtist)) score += 1
        }
        if (score > bestScore) {
          bestScore = score
          best = s
        }
      }
      return bestScore >= 2 ? best : null
    } catch {
      return null
    }
  }

  // ---- HTTP helpers ----

  private httpGet(url: string): Promise<string> {
    return new Promise((resolve, reject) => {
      fetch(url, {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36',
          'Accept-Language': 'en'
        },
        signal: AbortSignal.timeout(20_000)
      })
        .then((r) => (r.ok ? r.text() : Promise.reject(new Error(`Upstream HTTP ${r.status}`))))
        .then(resolve)
        .catch(reject)
    })
  }

  private httpJson(url: string, key: string, body: unknown): Promise<unknown> {
    return new Promise((resolve, reject) => {
      fetch(`${url}?key=${encodeURIComponent(key)}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/126'
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(20_000)
      })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`YouTube HTTP ${r.status}`))))
        .then(resolve)
        .catch(reject)
    })
  }
}

function canonical(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/[-–—:;./_+]/g, ' ')
    .replace(/\b(feat|ft|official|lyrics|video|remix|live|cover|version|edit)\b.*$/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
