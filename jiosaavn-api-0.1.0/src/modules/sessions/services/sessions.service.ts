import { HTTPException } from 'hono/http-exception'
import { SessionsRepository } from '../repositories/sessions.repository'
import type { TrackSnapshot } from '../repositories/sessions.repository'

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' // no I/O/0/1 confusion

export class SessionsService {
  private repo = new SessionsRepository()

  async create(owner: string, name: string, track: TrackSnapshot) {
    if (!track?.id) throw new HTTPException(400, { message: 'A track is required to start a session' })
    const code = this.newCode()
    const session = await this.repo.create(code, owner, name || 'Friend', track)
    return { code: session.code, members: session.members.length, state: session.state }
  }

  async join(code: string, deviceId: string, name: string) {
    const session = await this.repo.find(this.normalize(code))
    if (!session) throw new HTTPException(404, { message: 'Session not found — check the code' })
    const staleMs = Date.now() - session.state.updatedAt
    if (staleMs > 3 * 60 * 60 * 1000) {
      throw new HTTPException(410, { message: 'That session has expired' })
    }
    const updated = await this.repo.join(session.code, deviceId, name || 'Friend')
    if (!updated) throw new HTTPException(404, { message: 'Session not found — check the code' })
    return { code: updated.code, owner: updated.owner, members: updated.members.length, state: updated.state }
  }

  async get(code: string) {
    const session = await this.repo.find(this.normalize(code))
    if (!session) throw new HTTPException(404, { message: 'Session not found' })
    return { code: session.code, owner: session.owner, members: session.members.length, state: session.state }
  }

  async updateState(
    code: string,
    deviceId: string,
    patch: { track?: TrackSnapshot | null; positionMs?: number; playing?: boolean }
  ) {
    const session = await this.repo.updateState(this.normalize(code), deviceId, patch)
    if (!session) throw new HTTPException(404, { message: 'Session not found' })
    return { code: session.code, owner: session.owner, members: session.members.length, state: session.state }
  }

  private normalize(code: string): string {
    return (code ?? '').trim().toUpperCase().slice(0, 8)
  }

  private newCode(): string {
    let code = ''
    const bytes = new Uint8Array(6)
    crypto.getRandomValues(bytes)
    for (let i = 0; i < 6; i++) code += ALPHABET[bytes[i] % ALPHABET.length]
    return code
  }
}
