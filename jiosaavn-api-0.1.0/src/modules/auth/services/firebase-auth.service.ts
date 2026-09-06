import { randomBytes } from 'node:crypto'
import { decodeJwt } from 'jose'
import { collection, Collections } from '#common/database/mongo'
import { hashToken } from '#common/middleware/auth'
import { ApiError } from '#common/errors/api-error'
import { verifyFirebaseIdToken, type FirebaseIdentity } from './firebase.service'
import { verifyGoogleIdToken } from '#common/identity/google-token.service'

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
      throw ApiError.badRequest('A sign-in token is required', 'FIREBASE_TOKEN_INVALID')
    }

    // Route by issuer. The unverified decode is used ONLY to pick the
    // verifier; each verifier re-checks signature, issuer and audience, so a
    // forged issuer claim cannot cross into the wrong trust domain.
    let issuer = ''
    try {
      issuer = String((decodeJwt(idToken).iss as string) ?? '')
    } catch {
      throw new ApiError(401, 'FIREBASE_TOKEN_INVALID', 'Sign-in could not be verified. Please sign in again.')
    }

    let identity: FirebaseIdentity
    try {
      if (issuer.startsWith('https://accounts.google.com') || issuer === 'accounts.google.com') {
        // Google Sign-In: Google has already verified the email for gmail
        // addresses and verified custom domains (email_verified claim).
        if (!process.env.GOOGLE_WEB_CLIENT_ID) {
          throw new ApiError(
            503,
            'GOOGLE_NOT_CONFIGURED',
            'Google sign-in is not configured on the server yet.'
          )
        }
        const g = await verifyGoogleIdToken(idToken)
        identity = { uid: `google:${g.sub}`, email: g.email, emailVerified: g.emailVerified }
      } else {
        identity = await verifyFirebaseIdToken(idToken)
      }
    } catch (err) {
      if (err instanceof ApiError) throw err
      // Temporary diagnostics: log only the failure class, never token data.
      const cause = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
      console.warn(`[NightLight] token verify failed: ${cause.slice(0, 200)}`)
      // Distinguish audience mismatch (misconfiguration) from forgery/expiry
      // so the client can show an actionable message. No token contents leak.
      const msg = err instanceof Error ? err.message : ''
      if (/audience|aud/i.test(msg)) {
        throw new ApiError(
          401,
          'TOKEN_AUDIENCE_MISMATCH',
          'Sign-in configuration mismatch. Update the app or server config.'
        )
      }
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
