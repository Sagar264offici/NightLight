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
  if (/\b(?:acoustic|unplugged)\b/.test(t)) return 'acoustic'
  if (/\blive\b/.test(t)) return 'live'
  if (/\bremix\b/.test(t)) return 'remix'
  if (/\binstrumental\b/.test(t)) return 'instrumental'
  if (/\bpiano\b/.test(t)) return 'piano'
  if (/\bcover\b/.test(t)) return 'cover'
  if (/\b(?:sped up|sped)\b/.test(t)) return 'sped'
  if (/\b(?:slowed|slowed and reverb)\b/.test(t)) return 'slowed'
  if (/\breverb\b/.test(t)) return 'reverb'
  if (/\b(?:radio edit|edit)\b/.test(t)) return 'edit'
  if (/\bdemo\b/.test(t)) return 'demo'
  if (/\b(?:orchestral|symphony)\b/.test(t)) return 'orchestral'
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
  /** Normalized full query (lowercased, punctuation stripped) for exact-match bonus. */
  fullNormalized?: string
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
  if (!raw) return { artist: '', title: '', variant: null, rawQuery: raw, fullNormalized: '' }

  const lower = raw.toLowerCase()
  const fullNormalized = normaliseTitle(raw)

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
    cleaned = cleaned.replaceAll(new RegExp(keyword, 'gi'), '')
  }
  cleaned = cleaned.replaceAll(/\s+/g, ' ').trim()

  // 3. Try to split "artist title" vs "title artist".
  // Heuristic: if there are exactly 2 tokens, assume "artist title" (most common).
  // If there are 3+ tokens, try to detect which part is the artist.
  const tokens = cleaned.split(' ').filter(Boolean)

  let artist = ''
  let title = ''

  if (tokens.length === 0) {
    return { artist: '', title: '', variant, rawQuery: raw, fullNormalized }
  } else if (tokens.length === 1) {
    // Single token: treat as title (no artist info).
    // Keep fullNormalized so exact-match bonus can fire for queries like "Perfect".
    title = tokens[0]
  } else if (tokens.length === 2) {
    // Two tokens: "artist title" is most common.
    artist = tokens[0]
    title = tokens[1]
  } else if (tokens.length === 3) {
    // 3 tokens: 2-word artist + 1-word title is most common
    // ("Ed Sheeran Perfect"). Default to first-two = artist.
    artist = tokens.slice(0, 2).join(' ')
    title = tokens.slice(2).join(' ')
  } else {
    // 4+ tokens: split first half = artist, second half = title.
    const mid = Math.ceil(tokens.length / 2)
    artist = tokens.slice(0, mid).join(' ')
    title = tokens.slice(mid).join(' ')
  }

  return { artist, title, variant, rawQuery: raw, fullNormalized }
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
 *
 * Popularity signals (playCount, trending) ensure global hits surface first:
 * searching "Perfect" ranks Ed Sheeran's original (billions of plays) above
 * covers with identical titles.
 */
export function scoreCandidate(
  intent: QueryIntent,
  candidateTitle: string,
  candidateArtists: string[],
  candidateVersion: VersionType,
  playCount?: number | null,
  trendingBoost?: number,
  providerScore?: number | null,
  canonicalBoost?: number | null,
  canonicalArtistBoost?: number | null
): number {
  let score = 0

  const normTitle = normaliseTitle(intent.title)
  const candTitle = normaliseTitle(candidateTitle)
  const fullQuery = intent.fullNormalized ?? normaliseTitle(intent.rawQuery ?? '')

  // Exact full-query match (0-8 points). This is the strongest relevance signal:
  // query "perfect" vs title "Perfect" → +8; query "ed sheeran perfect" vs
  // title "Perfect" by "Ed Sheeran" is handled by title+artist below.
  if (fullQuery && candTitle) {
    if (candTitle === fullQuery) score += 8
    else if (candTitle.startsWith(`${fullQuery} `) || candTitle.startsWith(fullQuery)) score += 5
    else if (candTitle.includes(fullQuery) || fullQuery.includes(candTitle)) score += 3
  }

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
      if (candArtist === normArtist) {
        bestArtistScore = 4
        break
      }
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

  // Fallback whole-query evidence makes reversed/free-form queries work even
  // when the simple artist/title split is ambiguous (e.g. "Perfect Ed Sheeran"
  // or "Perfect live Ed Sheeran"). This supplements, rather than replaces,
  // exact title/artist scoring below.
  const rawTokens = new Set(
    normaliseTitle(intent.rawQuery)
      .split(' ')
      .filter((t) => t.length > 1)
  )
  if (rawTokens.size > 1) {
    const candidateTokens = new Set(
      normaliseTitle([candidateTitle, ...candidateArtists].join(' '))
        .split(' ')
        .filter((t) => t.length > 1)
    )
    let hits = 0
    for (const token of rawTokens) if (candidateTokens.has(token)) hits++
    const coverage = hits / rawTokens.size
    if (coverage >= 0.9) score += 4
    else if (coverage >= 0.75) score += 3
    else if (coverage >= 0.5) score += 1.5
  }

  // Version matching.
  if (intent.variant && intent.variant !== 'original') {
    // User explicitly requested a variant: that intent dominates fame.
    // A requested remix must beat a billion-play original.
    if (candidateVersion === intent.variant) {
      score += 9 // Perfect version match for an explicit request.
    } else if (candidateVersion === 'original') {
      score += 1 // Original is acceptable but not what was requested.
    } else {
      score += 0 // Wrong variant.
    }
  } else if (candidateVersion === 'original') {
    // User wants the original (no variant specified): strong boost for original.
    score += 5
  } else if (candidateVersion === 'acoustic' || candidateVersion === 'live') {
    score += 1 // Mild acceptance for acoustic/live.
  } else {
    score -= 2 // Penalty for remix/cover/etc.
  }

  // Popularity / trending signals (0-8 points). Global hits must outrank
  // obscure covers with identical titles. Log scale so billions don't dwarf
  // everything: playCount 10M ≈ +7, 1M ≈ +6, 100K ≈ +5, 10K ≈ +4.
  if (typeof playCount === 'number' && Number.isFinite(playCount) && playCount > 0) {
    const pop = Math.log10(playCount + 1)
    // pop ~4 (10K) → +2.4, ~6 (1M) → +4.8, ~9 (1B) → +7.2
    score += Math.min(8, Math.max(0, (pop - 3) * 1.2))
  }
  if (typeof trendingBoost === 'number' && Number.isFinite(trendingBoost) && trendingBoost > 0) {
    score += Math.min(4, trendingBoost)
  }

  // Provider retrieval score (0-4 points). Upstream autocomplete `score`
  // reflects the provider's own relevance/popularity model (e.g. Ed Sheeran's
  // "Perfect" ≈ 1.29M vs a regional namesake ≈ 193K). Log scale, bounded so
  // it can surface a strong candidate the text pool missed but never
  // outranks an exact intent match on its own.
  if (typeof providerScore === 'number' && Number.isFinite(providerScore) && providerScore > 0) {
    score += Math.min(4, Math.max(0, (Math.log10(providerScore + 1) - 4) * 1.5))
  }

  // Canonical confirmation (0-2 points): an independent provider resolved
  // the same recording. Small by design — confirmation, not dominance.
  if (typeof canonicalBoost === 'number' && Number.isFinite(canonicalBoost) && canonicalBoost > 0) {
    score += Math.min(2, canonicalBoost)
  }

  // Canonical-artist preference (0-3 points): when the query explicitly asks
  // for a version, recordings by the canonical artist(s) — artists holding an
  // exact-title ORIGINAL in the pool — outrank same-title versions by other
  // artists. Decided purely from pool evidence (originals present), never
  // hardcoded per artist. Covers stay eligible, just not first.
  if (typeof canonicalArtistBoost === 'number' && Number.isFinite(canonicalArtistBoost) && canonicalArtistBoost > 0) {
    score += Math.min(3, canonicalArtistBoost)
  }

  return score
}

export function normaliseTitle(raw: string): string {
  return raw
    .toLowerCase()
    .replaceAll(/\([^)]*\)/g, ' ')
    .replaceAll(/\[[^\]]*\]/g, ' ')
    .replaceAll(/[-–—:;.&_+"]/g, ' ')
    .replaceAll(
      /\b(feat|ft|from|official|lyrics|video|version|edit|extended|remix|acoustic|live|slowed|sped up|cover|instrumental|karaoke|reprise)\b.*$/g,
      ' '
    )
    .replaceAll(/\s+/g, ' ')
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
 * @param extractPlayCount Optional: playCount for popularity boost.
 * @param extractId Optional: stable id for trending-set lookup.
 * @param trendingIds Optional: set of trending song ids (lowercased) for +2 boost.
 * @param extractProviderScore Optional: upstream retrieval score (e.g.
 *   autocomplete `score`) for an additional bounded popularity signal.
 * @param extractCanonicalBoost Optional: cross-provider confirmation boost
 *   (small, bounded) when an independent provider resolved the same recording.
 * @param extractCanonicalArtistBoost Optional: bounded boost for recordings
 *   by canonical artists (computed from pool evidence, never hardcoded).
 */
export function rerankResults<T>(
  query: string,
  results: T[],
  extractTitle: (r: T) => string,
  extractArtists: (r: T) => string[],
  extractVersion: (r: T) => VersionType,
  extractPlayCount?: (r: T) => number | null | undefined,
  extractId?: (r: T) => string | undefined,
  trendingIds?: Set<string>,
  extractProviderScore?: (r: T) => number | null | undefined,
  extractCanonicalBoost?: (r: T) => number | null | undefined,
  extractCanonicalArtistBoost?: (r: T) => number | null | undefined
): T[] {
  const intent = extractIntent(query)
  if (!intent.title && !intent.fullNormalized) return results // No intent to rank against.

  return [...results]
    .map((r, idx) => ({ r, idx }))
    .sort((a, b) => {
      const playA = extractPlayCount ? extractPlayCount(a.r) : undefined
      const playB = extractPlayCount ? extractPlayCount(b.r) : undefined
      const idA = extractId ? extractId(a.r)?.toLowerCase() : undefined
      const idB = extractId ? extractId(b.r)?.toLowerCase() : undefined
      const trendA = idA && trendingIds?.has(idA) ? 2 : 0
      const trendB = idB && trendingIds?.has(idB) ? 2 : 0
      const provA = extractProviderScore ? extractProviderScore(a.r) : undefined
      const provB = extractProviderScore ? extractProviderScore(b.r) : undefined
      const canonA = extractCanonicalBoost ? extractCanonicalBoost(a.r) : undefined
      const canonB = extractCanonicalBoost ? extractCanonicalBoost(b.r) : undefined
      const canonArtistA = extractCanonicalArtistBoost ? extractCanonicalArtistBoost(a.r) : undefined
      const canonArtistB = extractCanonicalArtistBoost ? extractCanonicalArtistBoost(b.r) : undefined
      const scoreA = scoreCandidate(
        intent,
        extractTitle(a.r),
        extractArtists(a.r),
        extractVersion(a.r),
        playA,
        trendA,
        provA,
        canonA,
        canonArtistA
      )
      const scoreB = scoreCandidate(
        intent,
        extractTitle(b.r),
        extractArtists(b.r),
        extractVersion(b.r),
        playB,
        trendB,
        provB,
        canonB,
        canonArtistB
      )
      if (scoreB !== scoreA) return scoreB - scoreA
      // Tie-break: higher playCount first, then original order (stable).
      const nA = typeof playA === 'number' ? playA : -1
      const nB = typeof playB === 'number' ? playB : -1
      if (nB !== nA) return nB - nA
      return a.idx - b.idx
    })
    .map((x) => x.r)
}
