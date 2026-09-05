import { MongoClient, type Collection, type Db } from 'mongodb'

let client: MongoClient | null = null
let db: Db | null = null

/**
 * Collection names for NightLight user data. Music-provider data is never
 * stored here; MongoDB only holds application-owned user data.
 */
export const Collections = {
  USERS: 'users',
  LIKES: 'likes',
  RECENTLY_PLAYED: 'recentlyPlayed',
  SEARCH_HISTORY: 'searchHistory',
  PLAYLISTS: 'playlists',
  PLAYLIST_TRACKS: 'playlistTracks',
  PREFERENCES: 'preferences',
  SESSIONS: 'sessions'
} as const

async function ensureIndexes(database: Db) {
  const users = database.collection(Collections.USERS)
  await users.createIndex({ deviceId: 1 }, { unique: true })
  await users.createIndex({ tokenHash: 1 }, { unique: true })

  const likes = database.collection(Collections.LIKES)
  await likes.createIndex({ userId: 1, trackId: 1 }, { unique: true })
  await likes.createIndex({ userId: 1, createdAt: -1 })

  const recentlyPlayed = database.collection(Collections.RECENTLY_PLAYED)
  await recentlyPlayed.createIndex({ userId: 1, trackId: 1 }, { unique: true })
  await recentlyPlayed.createIndex({ userId: 1, playedAt: -1 })

  const searchHistory = database.collection(Collections.SEARCH_HISTORY)
  await searchHistory.createIndex({ userId: 1, createdAt: -1 })

  const playlists = database.collection(Collections.PLAYLISTS)
  await playlists.createIndex({ userId: 1, createdAt: -1 })

  const playlistTracks = database.collection(Collections.PLAYLIST_TRACKS)
  await playlistTracks.createIndex({ playlistId: 1, position: 1 }, { unique: true })
  await playlistTracks.createIndex({ playlistId: 1, trackId: 1 }, { unique: true })

  const preferences = database.collection(Collections.PREFERENCES)
  await preferences.createIndex({ userId: 1 }, { unique: true })

  const sessions = database.collection(Collections.SESSIONS)
  await sessions.createIndex({ code: 1 }, { unique: true })
  // Expire idle listen-together sessions after 3 hours.
  await sessions.createIndex({ updatedAt: 1 }, { expireAfterSeconds: 3 * 60 * 60 })
}

export async function connectMongo(): Promise<Db> {
  if (db) return db

  const uri = process.env.MONGODB_URI
  if (!uri) {
    throw new Error('MONGODB_URI environment variable is not set')
  }

  client = new MongoClient(uri, {
    serverSelectionTimeoutMS: 10000,
    connectTimeoutMS: 10000,
    maxPoolSize: 20,
    // Stable API is supported by the installed driver (mongodb ^7) and by
    // MongoDB Atlas, keeping the deployed runtime predictable.
    serverApi: { version: '1' }
  })

  await client.connect()
  db = client.db(process.env.MONGODB_DB_NAME || 'nightlight')
  await ensureIndexes(db)
  return db
}

export function getDb(): Db {
  if (!db) throw new Error('MongoDB has not been connected yet')
  return db
}

export function collection<T extends Document = Document>(name: string): Collection<T> {
  return getDb().collection<T>(name)
}

export async function closeMongo(): Promise<void> {
  await client?.close()
  client = null
  db = null
}

type Document = Record<string, unknown>