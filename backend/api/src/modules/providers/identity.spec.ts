import { describe, expect, it } from 'vitest'

import { canonicalKey, sameRecording } from './identity'

describe('canonicalKey', () => {
  it('normalizes case, punctuation and artist order', () => {
    expect(canonicalKey('Perfect', ['Ed Sheeran'])).toBe(canonicalKey('PERFECT!', ['ed sheeran']))
    expect(canonicalKey('Tum Hi Ho', ['Mithoon', 'Arijit Singh'])).toBe(
      canonicalKey('Tum Hi Ho', ['Arijit Singh', 'Mithoon'])
    )
  })

  it('strips parenthetical version markers', () => {
    expect(canonicalKey('Perfect (Acoustic)', ['Ed Sheeran'])).toBe(canonicalKey('Perfect', ['Ed Sheeran']))
  })
})

describe('sameRecording', () => {
  const base = { title: 'Perfect', artists: ['Ed Sheeran'], version: 'original' as const }

  it('matches the same recording across providers', () => {
    expect(
      sameRecording(base, {
        title: 'Perfect',
        artists: ['Ed Sheeran'],
        version: 'original',
        album: 'Divide',
        durationMs: 263000
      })
    ).toBe(true)
  })

  it('requires artist evidence (never title alone)', () => {
    expect(sameRecording(base, { title: 'Perfect', artists: ['Piano Covers'], version: 'original' })).toBe(false)
    expect(sameRecording(base, { title: 'Perfect', artists: [], version: 'original' })).toBe(false)
  })

  it('vetoes version mismatches: acoustic/live/remix are different recordings', () => {
    expect(sameRecording(base, { title: 'Perfect', artists: ['Ed Sheeran'], version: 'acoustic' })).toBe(false)
    expect(sameRecording(base, { title: 'Perfect', artists: ['Ed Sheeran'], version: 'live' })).toBe(false)
    expect(sameRecording(base, { title: 'Perfect', artists: ['Ed Sheeran'], version: 'remix' })).toBe(false)
  })

  it('lets agreeing durations override album re-tagging, vetoes drift', () => {
    expect(
      sameRecording(
        { ...base, album: 'Divide', durationMs: 263000 },
        { title: 'Perfect', artists: ['Ed Sheeran'], version: 'original', album: 'Divide (Deluxe)', durationMs: 264000 }
      )
    ).toBe(true)
    expect(
      sameRecording(
        { ...base, album: 'Divide', durationMs: 263000 },
        { title: 'Perfect', artists: ['Ed Sheeran'], version: 'original', album: 'Unrelated Album', durationMs: 263000 }
      )
    ).toBe(true)
    expect(
      sameRecording(
        { ...base, album: 'Divide' },
        { title: 'Perfect', artists: ['Ed Sheeran'], version: 'original', album: 'Unrelated Album' }
      )
    ).toBe(false)
    expect(
      sameRecording(
        { ...base, album: 'Divide', durationMs: 263000 },
        { title: 'Perfect', artists: ['Ed Sheeran'], version: 'original', album: 'Divide', durationMs: 300000 }
      )
    ).toBe(false)
  })
})
