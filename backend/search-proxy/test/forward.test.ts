import { describe, it } from 'node:test'
import assert from 'node:assert/strict'

import handler, {
  buildUpstreamUrl,
  clearRateLimitBuckets,
  headerValue,
  isRateLimited
} from '../api/forward.ts'

function makeRes() {
  const state = { code: 0, payload: null as unknown, headers: {} as Record<string, string> }
  return {
    state,
    res: {
      status(code) {
        state.code = code
        return this
      },
      json(payload) {
        state.payload = payload
      },
      setHeader(name, value) {
        state.headers[name] = value
      }
    }
  }
}

describe('buildUpstreamUrl', () => {
  it('builds allowed search calls', () => {
    const built = buildUpstreamUrl('search.getResults', { q: 'perfect', p: 0, n: 10 })
    assert.ok(built.url?.includes('__call=search.getResults'))
    assert.ok(built.url?.includes('q=perfect'))
    assert.equal(built.error, undefined)
  })

  it('rejects anything else', () => {
    assert.equal(buildUpstreamUrl('song.getDetails', { pids: 'x' }).error, 'call not allowed')
    assert.equal(buildUpstreamUrl('search.getResults', { q: 'x', evil: '1' }).error, 'param not allowed: evil')
    assert.equal(buildUpstreamUrl('search.getResults', { q: 'x'.repeat(201) }).error, 'param too long: q')
    assert.equal(buildUpstreamUrl('search.getResults', 'nope').error, 'params must be an object')
  })
})

describe('handler gating', () => {
  it('rejects non-POST', async () => {
    const { state, res } = makeRes()
    await handler({ method: 'GET', headers: {} }, res)
    assert.equal(state.code, 405)
  })

  it('requires the shared secret', async () => {
    process.env.FORWARD_SECRET = 's3cret'
    const { state, res } = makeRes()
    await handler({ method: 'POST', headers: {}, body: { call: 'search.getResults', params: { q: 'x' } } }, res)
    assert.equal(state.code, 403)
    const ok = makeRes()
    // Wrong secret must also fail (no network: secret check runs first).
    await handler(
      { method: 'POST', headers: { 'x-search-proxy-secret': 'wrong' }, body: { call: 'nope', params: {} } },
      ok.res
    )
    assert.equal(ok.state.code, 403)
  })

  it('rate-limits burst traffic', () => {
    clearRateLimitBuckets()
    let limited = 0
    for (let i = 0; i < 130; i++) {
      if (isRateLimited('1.2.3.4', 1000 + i * 10)) limited++
    }
    assert.ok(limited > 0)
  })

  it('reads headers case-insensitively', () => {
    assert.equal(headerValue({ 'X-Search-Proxy-Secret': 'a' }, 'x-search-proxy-secret'), 'a')
    assert.equal(headerValue({}, 'x-search-proxy-secret'), '')
  })
})
