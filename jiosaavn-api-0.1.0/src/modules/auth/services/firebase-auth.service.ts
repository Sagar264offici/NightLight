import { randomBytes } from 'node:crypto'
import { collection, Collections } from '#common/database/mongo'
import { hashToken } from '#common/middleware/auth'
import { ApiError } from '#common/errors/api-error'
import { verifyFirebaseIdToken, type FirebaseIdentity } from './firebase.service'

/**
 * Bridges Firebase Authentication identities to the existing NightLight
 * session system.
 *
 * Flow:
 *  1. Android authenticates with Firebase (sign-up / sign-in) and obtains an
 *     ID token.
 *  2. Android POSTs the ID token to /auth/firebase/exchange.
 *  3. After cryptographic verification (Google JWKS) the backend maps the
 *     Firebase identity to a NightLight user in MongoDB and mints the SAME
 *     hashed single-token session used by password login. Every downstream
 *     endpoint (playlists, likes, preferences) keeps working unchanged and
 *     ownership enforcement stays identical.
 *
 * Security contract:
 *  - ID tokens are verified against Google's public keys; issuer/audience are
 *    pinned to FIREBASE_PROJECT_ID. The project ID is not a secret.
 *  - Exchange REQUIRES a verified email. Firebase permits sign-ups with
 *    unverified addresses, so accepting them would let an attacker register
 *    someone@example.com and claim an existing NightLight account.
 *  - Linking policy: an existing account with the same email and no
 *    firebaseUid is claimed by the first verified Firebase identity. Because
 *    Firebase only issues tokens for an address after the mailbox is verified
 *    (or the Firebase password is known), a verified token is proof of mailbox
 *    control.
 *  - Session tokens are random 256-bit values; only their SHA-256 hash is
 *    stored. Logout rotates the hash, invalidating the client token.
 */

interface UserDoc {
  _id: { toString: () => string }
  email?: string
  firebaseUid?: string
  tokenHash?: string
  createdAt?: Date
}

export interface ExchangeResult {
  token: string
  user: { id: string; email: string; createdAt: string }
}

export class FirebaseAuthService {
  private users = () => collection(Collections.USERS)

  async exchange(idToken: string): Promise<ExchangeResult> {
    if (!idToken || typeof idToken !== 'string' || idToken.length < 32) {
      throw ApiError.badRequest('A Firebase ID token is required', 'FIREBASE_TOKEN_INVALID')
    }

    let identity: FirebaseIdentity
    try {
      identity = await verifyFirebaseIdToken(idToken)
    } catch {
      // Invalid, expired, wrong project, or forged tokens all land here with
      // one uniform rejection that leaks nothing about which check failed.
      throw new ApiError(401, 'FIREBASE_TOKEN_INVALID', 'Sign-in could not be verified. Please sign in again.')
    }

    if (!identity.emailVerified || !identity.email) {
      throw new ApiError(
        403,
        'EMAIL_NOT_VERIFIED',
        'Verify your email in the link we sent you, then continue.'
      )
    }

    const email = identity.email.trim().toLowerCase()
    const now = new Date()

    let user = (await this.users().findOne({ email })) as (UserDoc & { _id: { toString(): string } }) | null
    if (!user) {
      const inserted = await this.users().insertOne({
        email,
        firebaseUid: identity.uid,
        platform: 'android',
        createdAt: now,
        lastSeenAt: now
      })
      user = { _id: inserted.insertedId, email, firebaseUid: identity.uid, createdAt: now }
    } else if (user.firebaseUid && user.firebaseUid !== identity.uid) {
      // Different Firebase identity claiming the same verified email: the
      // mailbox was re-registered in Firebase. Re-point the account.
      await this.users().updateOne({ _id: user._id }, { $set: { firebaseUid: identity.uid } })
    }

    const rawToken = randomBytes(32).toString('base64url')
    await this.users().updateOne(
      { _id: user._id },
      { $set: { tokenHash: hashToken(rawToken), lastSeenAt: now } }
    )

    return {
      token: rawToken,
      user: {
        id: user._id.toString(),
        email,
        createdAt: (user.createdAt ?? now).toISOString()
      }
    }
  }
}
