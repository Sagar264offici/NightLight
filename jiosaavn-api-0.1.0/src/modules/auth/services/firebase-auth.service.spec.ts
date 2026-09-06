import { describe, expect, it } from 'vitest'
import { FirebaseAuthService } from './firebase-auth.service'

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
