import process from 'node:process'
import { afterEach, describe, expect, it } from 'vitest'

import { searchFetch } from './search-transport'

const FAKE_PROXY = 'https://proxy.example/forward'

afterEach(() => {
  delete process.env.SEARCH_PROXY_URL
  delete process.env.SEARCH_PROXY_SECRET
})

describe('searchFetch', () => {
  it('goes direct when no proxy is configured', async () => {
    // Direct path hits live JioSaavn; shape assertion only.
    const result = await searchFetch<{ total?: number }>('search.getResults' as never, { q: 'perfect', p: 0, n: 1 })
    expect(result.via).toBe('direct')
    expect(result.ok).toBe(true)
  })

  it('uses the proxy when configured', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const payload = { total: 1 }
    const okFetch = (() =>
      Promise.resolve(
        new Response(JSON.stringify({ success: true, data: payload }), { status: 200 })
      )) as unknown as typeof fetch
    const result = await searchFetch('search.getResults' as never, { q: 'perfect' }, okFetch)
    expect(result.via).toBe('proxy')
    expect(result.data).toEqual(payload)
  })

  it('falls back to direct when the proxy fails', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const badFetch = (() => Promise.resolve(new Response('nope', { status: 500 }))) as unknown as typeof fetch
    // Proxy 500 → falls back to direct (live). Only assert it resolves directly.
    const result = await searchFetch<{ total?: number }>(
      'search.getResults' as never,
      { q: 'perfect', p: 0, n: 1 },
      badFetch
    )
    expect(result.via).toBe('direct')
  })
})
