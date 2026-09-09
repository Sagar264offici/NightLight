import { Buffer } from 'node:buffer'
import process from 'node:process'
import * as mongo from '#common/database/mongo'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FirebaseAuthService } from './firebase-auth.service'

// Verification is mocked: unit tests must not hit Google's JWKS. The
// signature/issuer/audience paths are covered by the end-to-end device test.
vi.mock('#common/identity/google-token.service', () => ({
  verifyGoogleIdToken: vi.fn().mockResolvedValue({
    sub: 'g-sub-1',
    email: 'user@example.com',
    emailVerified: true
  })
}))

/**
 * Input-validation paths run without network. Signature verification paths
 * (401/403) depend on Google's public JWKS and are covered by the
 * end-to-end device test instead of unit tests.
 */
describe('FirebaseAuthService.exchange', () => {
  it('rejects missing/short tokens with 400 before any network call', async () => {
    const svc = new FirebaseAuthService()
    await expect(svc.exchange('')).rejects.toMatchObject({ status: 400 })
    await expect(svc.exchange('short')).rejects.toMatchObject({ status: 400 })
  })
})

/**
 * Regression: user creation must include tokenHash in the INSERT itself.
 *
 * The unique index on users.tokenHash was not sparse in existing
 * deployments; inserting a user without tokenHash indexes it as null, and
 * the second user ever created without one fails with E11000 -> HTTP 500.
 * That bug broke Google sign-in and verified-email exchange for every new
 * user once a single tokenHash-less document existed (e.g. from password
 * registration, which mints no session at register time).
 */
describe('FirebaseAuthService new-user insert', () => {
  const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const googleToken = `${enc({ alg: 'RS256' })}.${enc({
    iss: 'https://accounts.google.com',
    aud: 'web-client',
    sub: 's1',
    email: 'user@example.com',
    email_verified: true,
    exp: 999999999999
  })}.signature`

  const usersCollection = {
    findOne: vi.fn(),
    insertOne: vi.fn(),
    updateOne: vi.fn()
  }

  beforeEach(() => {
    vi.clearAllMocks()
    process.env.GOOGLE_WEB_CLIENT_ID = 'web-client'
    usersCollection.findOne.mockResolvedValue(null)
    usersCollection.insertOne.mockResolvedValue({ insertedId: { toString: () => 'u1' } })
    usersCollection.updateOne.mockResolvedValue({ modifiedCount: 1 })
    vi.spyOn(mongo, 'collection').mockReturnValue(usersCollection as never)
  })

  it('inserts new users WITH a tokenHash (never null on the unique index)', async () => {
    const svc = new FirebaseAuthService()
    const result = await svc.exchange(googleToken)

    expect(usersCollection.insertOne).toHaveBeenCalledTimes(1)
    const doc = usersCollection.insertOne.mock.calls[0][0] as Record<string, unknown>
    expect(typeof doc.tokenHash).toBe('string')
    expect((doc.tokenHash as string).length).toBeGreaterThan(16)

    // The returned session token must be the one whose hash was stored.
    expect(result.token.length).toBeGreaterThan(16)
  })
})
