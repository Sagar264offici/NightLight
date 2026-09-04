import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import type { Routes } from '#common/types'
import { rateLimit } from '#common/middleware/rate-limit'
import { requireUser } from '#common/middleware/auth'
import { ApiError } from '#common/errors/api-error'
import { AuthService } from '../services/auth.service'

const RegisterBody = z.object({
  deviceId: z.string().min(8).max(128).openapi({
    description: 'Stable per-install device identifier (e.g. UUID)'
  }),
  platform: z.string().max(32).optional().openapi({ default: 'android' }),
  appVersion: z.string().max(32).optional().openapi({ default: '1.0.0' })
})

export class AuthController implements Routes {
  public controller: OpenAPIHono
  private authService: AuthService

  constructor() {
    this.controller = new OpenAPIHono()
    this.authService = new AuthService()
  }

  public initRoutes() {
    // Registration is write-heavy and brute-forceable: rate limit per IP.
    this.controller.use('/register', rateLimit({ limit: 10, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/register',
        tags: ['Auth'],
        summary: 'Register a device',
        description: 'Creates an anonymous device account and returns a bearer token. ' +
          'The token is returned exactly once; store it securely on the device.',
        operationId: 'registerDevice',
        request: {
          body: {
            content: { 'application/json': { schema: RegisterBody } }
          }
        },
        responses: {
          200: {
            description: 'Device registered or re-authenticated',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    token: z.string(),
                    user: z.object({
                      id: z.string(),
                      deviceId: z.string(),
                      createdAt: z.string()
                    })
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const body = ctx.req.valid('json')
        const deviceId = body.deviceId.trim()
        if (deviceId.length < 8) throw ApiError.badRequest('Invalid device identifier', 'BAD_DEVICE_ID')

        const result = await this.authService.register(
          deviceId,
          body.platform || 'android',
          body.appVersion || '1.0.0'
        )

        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/auth/me',
        tags: ['Auth'],
        summary: 'Current user profile',
        operationId: 'getMe',
        responses: {
          200: {
            description: 'Profile for the authenticated device',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    id: z.string(),
                    stats: z.object({
                      likedTracks: z.number(),
                      playlists: z.number()
                    }),
                    preferences: z.any().nullable()
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const profile = await this.authService.getProfile(user.id)
        return ctx.json({ success: true, data: profile })
      }
    )
  }
}