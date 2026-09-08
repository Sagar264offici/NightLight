/**
 * Search result re-ranking: extracts user intent from the query, classifies
 * version variants, and boosts original recordings when the user doesn't
 * explicitly request a variant.
 */

// ---- Version Classification ----

export type VersionType =
  | 'original'
  | 'acoustic'
  | 'live'
  | 'remix'
  | 'instrumental'
  | 'piano'
  | 'cover'
  | 'sped'
  | 'slowed'
  | 'reverb'
  | 'edit'
  | 'demo'
  | 'orchestral'
  | 'unplugged'
  | 'karaoke'
  | 'nightcore'

/** Detect version markers in a title string. */
export function detectVersion(title: string): VersionType {
  const t = title.toLowerCase()
  if (/\b(acoustic|unplugged)\b/.test(t)) return 'acoustic'
  if (/\blive\b/.test(t)) return 'live'
  if (/\bremix\b/.test(t)) return 'remix'
  if (/\binstrumental\b/.test(t)) return 'instrumental'
  if (/\bpiano\b/.test(t)) return 'piano'
  if (/\bcover\b/.test(t)) return 'cover'
  if (/\b(sped up|sped)\b/.test(t)) return 'sped'
  if (/\b(slowed|slowed and reverb)\b/.test(t)) return 'slowed'
  if (/\breverb\b/.test(t)) return 'reverb'
  if (/\b(radio edit|edit)\b/.test(t)) return 'edit'
  if (/\bdemo\b/.test(t)) return 'demo'
  if (/\b(orchestral|symphony)\b/.test(t)) return 'orchestral'
  if (/\bkaraoke\b/.test(t)) return 'karaoke'
  if (/\bnightcore\b/.test(t)) return 'nightcore'
  return 'original'
}

// ---- Query Intent Extraction ----

export interface QueryIntent {
  artist: string
  title: string
  variant: VersionType | null
  rawQuery: string
}

/** Version keywords that appear in queries when users want a specific version. */
const VARIANT_KEYWORDS: Record<string, VersionType> = {
  acoustic: 'acoustic',
  unplugged: 'acoustic',
  live: 'live',
  remix: 'remix',
  instrumental: 'instrumental',
  piano: 'piano',
  cover: 'cover',
  'sped up': 'sped',
  sped: 'sped',
  slowed: 'slowed',
  reverb: 'reverb',
  'radio edit': 'edit',
  edit: 'edit',
  demo: 'demo',
  orchestral: 'orchestral',
  symphony: 'orchestral',
  karaoke: 'karaoke',
  nightcore: 'nightcore'
}

/**
 * Extracts artist, title, and variant intent from a search query.
 *
 * Examples:
 *   "Ed Sheeran Perfect"          → artist=Ed Sheeran, title=Perfect, variant=original
 *   "Ed Sheeran Perfect acoustic" → artist=Ed Sheeran, title=Perfect, variant=acoustic
 *   "Perfect live Ed Sheeran"     → artist=Ed Sheeran, title=Perfect, variant=live
 */
export function extractIntent(query: string): QueryIntent {
  const raw = query.trim()
  if (!raw) return { artist: '', title: '', variant: null, rawQuery: raw }

  const lower = raw.toLowerCase()

  // 1. Detect variant intent from the query.
  let variant: VersionType | null = null
  for (const [keyword, type] of Object.entries(VARIANT_KEYWORDS)) {
    if (lower.includes(keyword)) {
      variant = type
      break
    }
  }

  // 2. Remove variant keywords to isolate artist + title.
  let cleaned = lower
  for (const keyword of Object.keys(VARIANT_KEYWORDS)) {
    cleaned = cleaned.replace(new RegExp(keyword, 'gi'), '')
  }
  cleaned = cleaned.replace(/\s+/g, ' ').trim()

  // 3. Try to split "artist title" vs "title artist".
  // Heuristic: if there are exactly 2 tokens, assume "artist title" (most common).
  // If there are 3+ tokens, try to detect which part is the artist.
  const tokens = cleaned.split(' ').filter(Boolean)

  let artist = ''
  let title = ''

  if (tokens.length === 0) {
    return { artist: '', title: '', variant, rawQuery: raw }
  } else if (tokens.length === 1) {
    // Single token: treat as title (no artist info).
    title = tokens[0]
  } else if (tokens.length === 2) {
    // Two tokens: "artist title" is most common.
    artist = tokens[0]
    title = tokens[1]
  } else {
    // 3+ tokens: try common patterns.
    // "Ed Sheeran Perfect" → artist="Ed Sheeran", title="Perfect"
    // "Perfect Ed Sheeran" → this is less common; we keep it as-is.
    // Simple heuristic: the LAST token(s) after the first significant word(s) is the title.
    // For now, use a simple split: first half = artist, second half = title.
    const mid = Math.ceil(tokens.length / 2)
    artist = tokens.slice(0, mid).join(' ')
    title = tokens.slice(mid).join(' ')
  }

  return { artist, title, variant, rawQuery: raw }
}

// ---- Scoring ----

/**
 * Score a search result against the user's query intent.
 * Higher score = better match.
 *
 * When variant is null (user didn't request a version), original recordings
 * get a significant boost over variants.
 *
 * When variant is specified, matching variants get the boost instead.
 */
export function scoreCandidate(
  intent: QueryIntent,
  candidateTitle: string,
  candidateArtists: string[],
  candidateVersion: VersionType
): number {
  let score = 0

  const normTitle = normaliseTitle(intent.title)
  const candTitle = normaliseTitle(candidateTitle)

  // Title match (0-6 points).
  if (normTitle && candTitle) {
    if (candTitle === normTitle) score += 6
    else if (candTitle.includes(normTitle) || normTitle.includes(candTitle)) score += 4
    else {
      // Token overlap.
      const wTokens = new Set(normTitle.split(' '))
      const cTokens = new Set(candTitle.split(' '))
      let overlap = 0
      for (const t of wTokens) if (cTokens.has(t)) overlap++
      const union = new Set([...wTokens, ...cTokens]).size
      const ratio = union > 0 ? overlap / union : 0
      if (ratio >= 0.8) score += 3
      else if (ratio >= 0.5) score += 2
      else if (ratio >= 0.3) score += 1
    }
  }

  // Artist match (0-4 points).
  if (intent.artist && candidateArtists.length > 0) {
    const normArtist = normaliseTitle(intent.artist)
    let bestArtistScore = 0
    for (const ca of candidateArtists) {
      const candArtist = normaliseTitle(ca)
      if (!candArtist) continue
      if (candArtist === normArtist) { bestArtistScore = 4; break }
      if (candArtist.includes(normArtist) || normArtist.includes(candArtist)) bestArtistScore = 3
      else {
        const wTokens = new Set(normArtist.split(' '))
        const cTokens = new Set(candArtist.split(' '))
        let overlap = 0
        for (const t of wTokens) if (cTokens.has(t)) overlap++
        if (overlap > 0) bestArtistScore = Math.max(bestArtistScore, 2)
      }
    }
    score += bestArtistScore
  }

  // Version matching (0-5 points).
  if (intent.variant && intent.variant !== 'original') {
    // User explicitly requested a variant.
    if (candidateVersion === intent.variant) {
      score += 5 // Perfect version match.
    } else if (candidateVersion === 'original') {
      score += 1 // Original is acceptable but not what was requested.
    } else {
      score += 0 // Wrong variant.
    }
  } else {
    // User wants the original (no variant specified).
    if (candidateVersion === 'original') {
      score += 5 // Strong boost for original.
    } else if (candidateVersion === 'acoustic' || candidateVersion === 'live') {
      score += 1 // Mild acceptance for acoustic/live.
    } else {
      score -= 2 // Penalty for remix/cover/etc.
    }
  }

  return score
}

function normaliseTitle(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/[-–—:;.&_+"]/g, ' ')
    .replace(/\b(feat|ft|from|official|lyrics|video|version|edit|extended|remix|acoustic|live|slowed|sped up|cover|instrumental|karaoke|reprise)\b.*$/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

// ---- Re-ranking Entry Point ----

/**
 * Re-ranks an array of search results based on user intent.
 * Returns a new array (does not mutate the input).
 *
 * @param query   The raw search query.
 * @param results The original JioSaavn results.
 * @param extractTitle  Function to extract the title from a result.
 * @param extractArtists  Function to extract artist names from a result.
 * @param extractVersion  Function to extract the version from a result.
 */
export function rerankResults<T>(
  query: string,
  results: T[],
  extractTitle: (r: T) => string,
  extractArtists: (r: T) => string[],
  extractVersion: (r: T) => VersionType
): T[] {
  const intent = extractIntent(query)
  if (!intent.title) return results // No intent to rank against.

  return [...results].sort((a, b) => {
    const scoreA = scoreCandidate(intent, extractTitle(a), extractArtists(a), extractVersion(a))
    const scoreB = scoreCandidate(intent, extractTitle(b), extractArtists(b), extractVersion(b))
    return scoreB - scoreA
  })
}
