import type { Document } from 'mongodb'
import { collection, Collections } from '#common/database/mongo'

export interface MemberRecord {
  deviceId: string
  name: string
  joinedAt: number
}

export interface TrackSnapshot {
  id: string
  name: string
  artists: string
  album: string
  imageUrl: string
  duration: number
  year: string
}

export interface SessionStateRecord {
  track: TrackSnapshot | null
  positionMs: number
  playing: boolean
  updatedAt: number
}

export interface SessionRecord {
  code: string
  owner: string
  members: MemberRecord[]
  state: SessionStateRecord
  createdAt: number
}

interface SessionDoc extends Document {
  code: string
  owner: string
  members: MemberRecord[]
  state: SessionStateRecord
  createdAt: number
  updatedAt?: number
}

export class SessionsRepository {
  private collection() {
    return collection<SessionDoc>(Collections.SESSIONS)
  }

  async create(code: string, owner: string, name: string, track: TrackSnapshot): Promise<SessionRecord> {
    const now = Date.now()
    const doc: SessionDoc = {
      code,
      owner,
      members: [{ deviceId: owner, name, joinedAt: now }],
      state: {
        track,
        positionMs: 0,
        playing: true,
        updatedAt: now
      },
      createdAt: now
    }
    await this.collection().insertOne(doc)
    return this.lean(doc)
  }

  async find(code: string): Promise<SessionRecord | null> {
    const doc = await this.collection().findOne({ code })
    return doc ? this.lean(doc) : null
  }

  async join(code: string, deviceId: string, name: string): Promise<SessionRecord | null> {
    const doc = await this.collection().findOne({ code })
    if (!doc) return null
    const already = doc.members.some((m) => m.deviceId === deviceId)
    if (!already) {
      await this.collection().updateOne(
        { code },
        // Typed collections reject dotted updates; this doc is ours.
        { $push: { members: { deviceId, name, joinedAt: Date.now() } } } as never
      )
    }
    const fresh = await this.collection().findOne({ code })
    return fresh ? this.lean(fresh) : null
  }

  async updateState(
    code: string,
    deviceId: string,
    patch: { track?: TrackSnapshot | null; positionMs?: number; playing?: boolean }
  ): Promise<SessionRecord | null> {
    const now = Date.now()
    const update: Record<string, unknown> = { 'state.updatedAt': now, updatedAt: now }
    if (patch.track !== undefined) update['state.track'] = patch.track
    if (patch.positionMs !== undefined) update['state.positionMs'] = Math.max(0, Math.round(patch.positionMs))
    if (patch.playing !== undefined) update['state.playing'] = Boolean(patch.playing)
    await this.collection().updateOne({ code, owner: deviceId }, { $set: update } as never)
    const fresh = await this.collection().findOne({ code })
    return fresh ? this.lean(fresh) : null
  }

  async touch(code: string): Promise<void> {
    await this.collection().updateOne({ code }, { $set: { updatedAt: Date.now() } })
  }

  private lean(doc: SessionDoc): SessionRecord {
    return {
      code: doc.code,
      owner: doc.owner,
      members: doc.members ?? [],
      state: doc.state,
      createdAt: doc.createdAt
    }
  }
}
