import process from 'node:process'

import { useFetch } from '#common/helpers'
import type { Endpoints } from '#common/constants'

type SearchEndpoint = typeof Endpoints.search.songs | typeof Endpoints.search.all

export interface SearchFetchResult<T> {
  data: T
  ok: boolean
  via: 'direct' | 'proxy'
}

type FetchImpl = typeof fetch

/**
 * Search-rung transport with Mumbai-egress option.
 *
 * JioSaavn ranks by server-IP geography: from US egress, global hits vanish
 * from results entirely (measured: Ed Sheeran's "Perfect" absent from both
 * getResults and autocomplete). When SEARCH_PROXY_URL (+SECRET) is set,
 * the two ranking-sensitive rungs route through the bom1 forwarder and
 * recall matches Indian users. Proxy failure always falls back to direct —
 * a down proxy must never break search.
 */
export async function searchFetch<T>(
  endpoint: SearchEndpoint,
  params: Record<string, string | number>,
  fetchImpl: FetchImpl = fetch
): Promise<SearchFetchResult<T>> {
  const proxyUrl = process.env.SEARCH_PROXY_URL
  const proxySecret = process.env.SEARCH_PROXY_SECRET
  if (proxyUrl && proxySecret) {
    try {
      const response = await fetchImpl(proxyUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-search-proxy-secret': proxySecret
        },
        body: JSON.stringify({ call: endpoint.toString(), params }),
        signal: AbortSignal.timeout(12000)
      })
      const body = (await response.json()) as { success?: boolean; data?: T }
      if (response.ok && body?.success) {
        return { data: body.data as T, ok: true, via: 'proxy' }
      }
    } catch {
      // Fall through to direct below.
    }
  }
  const direct = await useFetch<T>({ endpoint, params })
  return { ...direct, via: 'direct' }
}
