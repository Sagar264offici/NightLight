import type { VercelRequest, VercelResponse } from '@vercel/node'

/**
 * Control endpoint: zero logic, zero upstream, zero env reads, zero imports
 * from forward.ts (deliberately independent — if THIS 500s, the fault is
 * platform/config, not handler code).
 */
const BUILD = '2026-09-09.3'

export default function handler(_req: VercelRequest, res: VercelResponse): void {
  res.status(200).json({ success: true, build: BUILD, runtime: process.version })
}
