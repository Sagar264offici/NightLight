import { OpenAPIHono } from '@hono/zod-openapi'
import { apiReference } from '@scalar/hono-api-reference'
import { cors } from 'hono/cors'
import { logger } from 'hono/logger'
import { prettyJSON } from 'hono/pretty-json'
import { HTTPException } from 'hono/http-exception'
import { ZodError } from 'zod'
import { Home } from './pages/home'
import type { Routes } from '#common/types'
import { ApiError } from '#common/errors/api-error'
import { rateLimit } from '#common/middleware/rate-limit'
import { connectMongo, getDb } from '#common/database/mongo'

export class App {
  private app: OpenAPIHono

  constructor(routes: Routes[]) {
    this.app = new OpenAPIHono()

    this.initializeGlobalMiddlewares()
    this.initializeRoutes(routes)
    this.initializeSwaggerUI()
    this.initializeRouteFallback()
    this.initializeErrorHandler()
  }

  private initializeRoutes(routes: Routes[]) {
    routes.forEach((route) => {
      route.initRoutes()
      const base = `/api${route.pathPrefix ?? ''}`
      this.app.route(base, route.controller)
    })

    this.app.get('/api/health', async (ctx) => {
      let dbOk = false
      try {
        await getDb().command({ ping: 1 })
        dbOk = true
      } catch {
        dbOk = false
      }
      return ctx.json({ success: true, data: { status: 'ok', database: dbOk ? 'connected' : 'unavailable' } })
    })

    this.app.route('/', Home)
  }

  private initializeGlobalMiddlewares() {
    this.app.use(logger())
    this.app.use(prettyJSON())
    this.app.use(cors())
    // The music-proxy search endpoints are external and costly: rate limit them.
    this.app.use('/api/search*', rateLimit({ limit: 60, windowMs: 60_000 }))
    // Search results are public and change slowly: let OkHttp serve repeats
    // instantly from the on-device HTTP cache (repeat queries become ~0 ms).
    this.app.use('/api/search*', async (ctx, next) => {
      await next()
      if (ctx.res.status >= 200 && ctx.res.status < 400) {
        ctx.header('Cache-Control', 'public, max-age=30')
      }
    })
  }

  private initializeSwaggerUI() {
    this.app.doc31('/swagger', (c) => {
      const { protocol: urlProtocol, hostname, port } = new URL(c.req.url)
      const protocol = c.req.header('x-forwarded-proto') ? `${c.req.header('x-forwarded-proto')}:` : urlProtocol

      return {
        openapi: '3.1.0',

        info: {
          version: '1.0.0',
          title: 'JioSaavn API',
          description: `# Introduction 
        \nJioSaavn API, accessible at [saavn.dev](https://saavn.dev), is an unofficial API that allows users to download high-quality songs from [JioSaavn](https://jiosaavn.com). 
        It offers a fast, reliable, and easy-to-use API for developers. \n`
        },
        servers: [{ url: `${protocol}//${hostname}${port ? `:${port}` : ''}`, description: 'Current environment' }]
      }
    })

    this.app.get(
      '/docs',
      apiReference({
        pageTitle: 'JioSaavn API Documentation',
        theme: 'deepSpace',
        isEditable: false,
        layout: 'modern',
        darkMode: true,
        metaData: {
          applicationName: 'JioSaavn API',
          author: 'Sumit Kolhe',
          creator: 'Sumit Kolhe',
          publisher: 'Sumit Kolhe',
          robots: 'index, follow',
          description:
            'JioSaavn API is an unofficial wrapper written in TypeScript for jiosaavn.com providing programmatic access to a vast library of songs, albums, artists, playlists, and more.'
        },
        url: '/swagger'
      })
    )
  }

  private initializeRouteFallback() {
    this.app.notFound((ctx) => {
      return ctx.json({ success: false, message: 'route not found, check docs at https://saavn.dev/docs' }, 404)
    })
  }

  private initializeErrorHandler() {
    this.app.onError((err, ctx) => {
      if (err instanceof ApiError) {
        return ctx.json({ success: false, message: err.message, code: err.code }, err.status)
      }
      if (err instanceof HTTPException) {
        return ctx.json({ success: false, message: err.message }, err.status || 500)
      }
      if (err instanceof ZodError) {
        return ctx.json({ success: false, message: 'Invalid request', code: 'BAD_REQUEST' }, 400)
      }
      // Never leak internals to clients; log server-side only.
      console.error('[NightLight] unhandled error:', err)
      return ctx.json({ success: false, message: 'Something went wrong', code: 'INTERNAL_ERROR' }, 500)
    })
  }

  public getApp() {
    return this.app
  }
}
