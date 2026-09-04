import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { HTTPException } from 'hono/http-exception'
import type { Routes } from '#common/types'
import { rateLimit } from '#common/middleware/rate-limit'
import { ImportService } from '../services/import.service'

const ImportBody = z.object({
  url: z.string().url().max(2048).openapi({
    description: 'Public Spotify or YouTube playlist URL',
    example: 'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'
  }),
  limit: z.number().int().min(1).max(100).optional().default(60)
})

/**
 * Converts a public Spotify / YouTube playlist into matched JioSaavn songs.
 * Track titles are matched server-side so the app only ever calls one endpoint.
 */
export class ImportController implements Routes {
  public controller: OpenAPIHono
  private importService: ImportService

  constructor() {
    this.controller = new OpenAPIHono()
    this.importService = new ImportService()
  }

  public initRoutes() {
    // Import does many upstream searches: keep it per-IP throttled.
    this.controller.use('/import*', rateLimit({ limit: 6, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/import/playlist',
        tags: ['Import'],
        summary: 'Import a Spotify/YouTube playlist',
        description:
          'Resolves a public playlist URL, matches every track against the ' +
          'music library, and returns the playable songs ready to store locally.',
        operationId: 'importPlaylist',
        request: { body: { content: { 'application/json': { schema: ImportBody } } } },
        responses: {
          200: {
            description: 'Matched playlist',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    source: z.string(),
                    playlistName: z.string(),
                    totalTracks: z.number(),
                    matched: z.number(),
                    unmatched: z.array(z.string()),
                    results: z.array(z.record(z.string(), z.unknown()))
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { url, limit } = ctx.req.valid('json')
        try {
          const data = await this.importService.importPlaylist(url, limit)
          return ctx.json({ success: true, data })
        } catch (e) {
          const message = e instanceof Error ? e.message : 'Import failed'
          throw new HTTPException(422, { message })
        }
      }
    )
  }
}
