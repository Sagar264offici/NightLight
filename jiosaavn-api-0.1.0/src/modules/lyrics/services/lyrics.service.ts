export interface LyricsLine {
  timeMs: number | null
  text: string
}

export interface LyricsResult {
  available: boolean
  instrumental: boolean
  timed: boolean
  lines: LyricsLine[]
}

interface LyricsArgs {
  title: string
  artist: string
  album: string
  durationMs: number
}

const UA = 'NightLightApp/1.0 (https://github.com/nightlight; contact: developer)'

interface LrcLibEntry {
  syncedLyrics?: string | null
  plainLyrics?: string | null
  instrumental?: boolean
  duration?: number
  trackName?: string
  artistName?: string
  albumName?: string
}

/** Fetches synchronized lyrics from the free LRCLIB database. */
export class LyricsService {
  async fetchLyrics({ title, artist, album, durationMs }: LyricsArgs): Promise<LyricsResult> {
    const durationSec = durationMs ? Math.round(durationMs / 1000) : 0

    let data: LrcLibEntry | null = null
    try {
      // Fast path: exact (album + duration) match.
      const q = new URLSearchParams({
        artist_name: artist || '',
        track_name: title,
        album_name: album || '',
        duration: durationSec ? String(durationSec) : ''
      })
      const r = await fetch(`https://lrclib.net/api/get?${q.toString()}`, {
        headers: { 'User-Agent': UA },
        signal: AbortSignal.timeout(10_000)
      })
      if (r.ok) {
        data = (await r.json()) as LrcLibEntry
      }
    } catch {
      data = null
    }

    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      // Fallback: search by title + artist and pick the closest entry.
      data = await this.searchLrcLib(title, artist, album || '', durationSec)
    }

    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      return { available: false, instrumental: false, timed: false, lines: [] }
    }

    const raw = data.syncedLyrics || data.plainLyrics || ''
    if (!raw.trim()) {
      return { available: false, instrumental: false, timed: false, lines: [] }
    }
    if (data.instrumental) {
      return { available: true, instrumental: true, timed: false, lines: [] }
    }

    const timed = Boolean(data.syncedLyrics)
    const lines = parseLrc(raw, timed)

    return { available: true, instrumental: false, timed, lines }
  }

  /** LRCLIB search with fuzzy duration/album tolerance. */
  private async searchLrcLib(
    title: string,
    artist: string,
    album: string,
    durationSec: number
  ): Promise<LrcLibEntry | null> {
    const q = new URLSearchParams({
      track_name: title,
      artist_name: artist || '',
      duration: durationSec ? String(durationSec) : ''
    })
    try {
      const r = await fetch(`https://lrclib.net/api/search?${q.toString()}`, {
        headers: { 'User-Agent': UA },
        signal: AbortSignal.timeout(10_000)
      })
      if (!r.ok) return null
      const list = (await r.json()) as LrcLibEntry[]
      if (!Array.isArray(list) || list.length === 0) return null

      const wantTitle = canon(title)
      const wantArtist = canon(artist)
      let best: LrcLibEntry | null = null
      let bestScore = -1
      for (const e of list) {
        let score = 0
        const t = canon(e.trackName ?? '')
        const a = canon(e.artistName ?? '')
        if (t === wantTitle) score += 4
        else if (t && wantTitle && (t.includes(wantTitle) || wantTitle.includes(t))) score += 2
        if (a && wantArtist && (a === wantArtist || a.includes(wantArtist) || wantArtist.includes(a))) score += 1
        if (durationSec && typeof e.duration === 'number' && Math.abs(e.duration - durationSec) <= 6) score += 1
        if (album && e.albumName && canon(e.albumName) === canon(album)) score += 1
        // Prefer synchronized uploads so the UI can karaoke-highlight.
        if (e.syncedLyrics) score += 2
        if (!e.syncedLyrics && !e.plainLyrics) score -= 3
        if (score > bestScore) {
          bestScore = score
          best = e
        }
      }
      return bestScore >= 3 ? best : null
    } catch {
      return null
    }
  }
}

function canon(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/\([^)]*\)/g, ' ')
    .replace(/[-–—:;.&_+]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/** Removes non-timestamp metadata tags ([ti:], [ar:], [by:], [offset:] ...). */
function stripMetaTags(raw: string): string {
  return raw.replace(/\[(?!\d{1,2}:\d{2})[^\]]*\]/g, '')
}

function parseLrc(raw: string, timed: boolean): LyricsLine[] {
  const out: LyricsLine[] = []
  for (const rawLine of raw.split('\n')) {
    const line = rawLine.trim()
    if (!line) continue
    const stamps: number[] = []
    // [mm:ss.xx] timestamps may appear multiple times per line.
    const re = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g
    let m: RegExpExecArray | null
    let text = line
    while ((m = re.exec(line)) !== null) {
      const min = Number(m[1])
      const sec = Number(m[2])
      const frac = m[3] ? Number(m[3].padEnd(3, '0')) : 0
      stamps.push((min * 60 + sec) * 1000 + frac)
      text = line.slice(re.lastIndex)
    }
    text = stripMetaTags(text)
      .replace(/^[\s:：\-–—|]+/, '')
      .replace(/\s+/g, ' ')
      .trim()
    if (!text) continue
    if (timed && stamps.length) {
      for (const t of stamps) out.push({ timeMs: t, text })
    } else {
      out.push({ timeMs: null, text })
    }
  }
  return out
}
