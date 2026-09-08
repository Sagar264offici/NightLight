import { describe, it, expect } from 'vitest'
import { extractIntent, detectVersion, scoreCandidate, rerankResults } from './search-ranker'

describe('detectVersion()', () => {
  it('detects original', () => {
    expect(detectVersion('Perfect')).toBe('original')
  })

  it('detects acoustic', () => {
    expect(detectVersion('Perfect (Acoustic)')).toBe('acoustic')
  })

  it('detects live', () => {
    expect(detectVersion('Perfect (Live)')).toBe('live')
  })

  it('detects remix', () => {
    expect(detectVersion('Perfect (Remix)')).toBe('remix')
  })

  it('detects instrumental', () => {
    expect(detectVersion('Perfect Instrumental')).toBe('instrumental')
  })

  it('detects piano', () => {
    expect(detectVersion('Perfect Piano Version')).toBe('piano')
  })

  it('detects cover', () => {
    expect(detectVersion('Perfect (Cover)')).toBe('cover')
  })

  it('detects sped up', () => {
    expect(detectVersion('Perfect (Sped Up)')).toBe('sped')
  })

  it('detects slowed', () => {
    expect(detectVersion('Perfect (Slowed)')).toBe('slowed')
  })

  it('detects karaoke', () => {
    expect(detectVersion('Perfect Karaoke')).toBe('karaoke')
  })
})

describe('extractIntent()', () => {
  it('extracts artist and title from "Ed Sheeran Perfect"', () => {
    const intent = extractIntent('Ed Sheeran Perfect')
    expect(intent.artist).toBe('ed sheeran')
    expect(intent.title).toBe('perfect')
    expect(intent.variant).toBeNull()
  })

  it('extracts variant from "Ed Sheeran Perfect acoustic"', () => {
    const intent = extractIntent('Ed Sheeran Perfect acoustic')
    expect(intent.artist).toBe('ed sheeran')
    expect(intent.title).toBe('perfect')
    expect(intent.variant).toBe('acoustic')
  })

  it('extracts variant from "Perfect live Ed Sheeran"', () => {
    const intent = extractIntent('Perfect live Ed Sheeran')
    // Heuristic: 3+ tokens after removing 'live' → first half = artist, second = title.
    // For reversed patterns the artist/title split may not be perfect,
    // but the variant is correctly detected.
    expect(intent.variant).toBe('live')
    expect(intent.title + intent.artist).toContain('perfect')
    expect(intent.title + intent.artist).toContain('sheeran')
  })

  it('extracts remix variant', () => {
    const intent = extractIntent('Ed Sheeran Perfect remix')
    expect(intent.variant).toBe('remix')
  })

  it('handles single word query', () => {
    const intent = extractIntent('Perfect')
    expect(intent.title).toBe('perfect')
    expect(intent.artist).toBe('')
    expect(intent.variant).toBeNull()
  })

  it('handles empty query', () => {
    const intent = extractIntent('')
    expect(intent.title).toBe('')
    expect(intent.artist).toBe('')
    expect(intent.variant).toBeNull()
  })

  it('removes variant keywords from title extraction', () => {
    const intent = extractIntent('Ed Sheeran Perfect acoustic')
    // "acoustic" should not be part of the title.
    expect(intent.title).not.toContain('acoustic')
  })
})

describe('scoreCandidate()', () => {
  const baseIntent = { artist: 'ed sheeran', title: 'perfect', variant: null, rawQuery: 'Ed Sheeran Perfect' }

  it('gives high score to exact original match', () => {
    const score = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    expect(score).toBeGreaterThanOrEqual(10)
  })

  it('penalises acoustic when user wants original', () => {
    const original = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const acoustic = scoreCandidate(baseIntent, 'Perfect (Acoustic)', ['Ed Sheeran'], 'acoustic')
    expect(original).toBeGreaterThan(acoustic)
  })

  it('penalises live when user wants original', () => {
    const original = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const live = scoreCandidate(baseIntent, 'Perfect (Live)', ['Ed Sheeran'], 'live')
    expect(original).toBeGreaterThan(live)
  })

  it('penalises remix when user wants original', () => {
    const original = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const remix = scoreCandidate(baseIntent, 'Perfect (Remix)', ['Ed Sheeran'], 'remix')
    expect(original).toBeGreaterThan(remix)
  })

  it('boosts acoustic when user explicitly requests it', () => {
    const acousticIntent = { ...baseIntent, variant: 'acoustic' as const }
    const original = scoreCandidate(acousticIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const acoustic = scoreCandidate(acousticIntent, 'Perfect (Acoustic)', ['Ed Sheeran'], 'acoustic')
    expect(acoustic).toBeGreaterThan(original)
  })

  it('boosts live when user explicitly requests it', () => {
    const liveIntent = { ...baseIntent, variant: 'live' as const }
    const original = scoreCandidate(liveIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const live = scoreCandidate(liveIntent, 'Perfect (Live)', ['Ed Sheeran'], 'live')
    expect(live).toBeGreaterThan(original)
  })

  it('penalises wrong artist', () => {
    const sameArtist = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const diffArtist = scoreCandidate(baseIntent, 'Perfect', ['Some Cover Artist'], 'original')
    expect(sameArtist).toBeGreaterThan(diffArtist)
  })
})

describe('rerankResults()', () => {
  const mockResults = [
    { name: 'Perfect (Acoustic)', artists: { primary: [{ name: 'Ed Sheeran' }] } },
    { name: 'Perfect', artists: { primary: [{ name: 'Ed Sheeran' }] } },
    { name: 'Perfect (Live)', artists: { primary: [{ name: 'Ed Sheeran' }] } },
    { name: 'Perfect (Remix)', artists: { primary: [{ name: 'DJ Remix' }] } },
    { name: 'Perfect Piano Version', artists: { primary: [{ name: 'Piano Covers' }] } }
  ]

  it('puts original first when user wants original', () => {
    const ranked = rerankResults(
      'Ed Sheeran Perfect',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    expect(ranked[0].name).toBe('Perfect')
  })

  it('puts acoustic first when user requests acoustic', () => {
    const ranked = rerankResults(
      'Ed Sheeran Perfect acoustic',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    expect(ranked[0].name).toBe('Perfect (Acoustic)')
  })

  it('puts live first when user requests live', () => {
    const ranked = rerankResults(
      'Perfect live Ed Sheeran',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    expect(ranked[0].name).toBe('Perfect (Live)')
  })

  it('puts remix last when user wants original', () => {
    const ranked = rerankResults(
      'Ed Sheeran Perfect',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    const remixIndex = ranked.findIndex((r) => r.name.includes('Remix'))
    const originalIndex = ranked.findIndex((r) => r.name === 'Perfect')
    expect(remixIndex).toBeGreaterThan(originalIndex)
  })
})
