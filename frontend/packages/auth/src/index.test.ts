import type { User } from 'oidc-client-ts'
import { describe, expect, it, vi } from 'vitest'
import { CALLBACK_PATH, ConsoleAuth, LOGOUT_PATH, tenantCodeOfHost, type UserManagerLike } from './index'

function user(overrides: Partial<{ expires_at: number; state: unknown }> = {}): User {
  return {
    access_token: 'access-1',
    expires_at: 1_000,
    expired: false,
    profile: { sub: 'kc-1', email: 'ada@example.test', name: 'Ada Lovelace' },
    state: overrides.state,
    ...overrides,
  } as unknown as User
}

function manager(overrides: Partial<UserManagerLike> = {}): UserManagerLike {
  return {
    signinRedirect: vi.fn(async () => {}),
    signinRedirectCallback: vi.fn(async () => user()),
    signinSilent: vi.fn(async () => user({ expires_at: 2_000 })),
    signoutRedirect: vi.fn(async () => {}),
    removeUser: vi.fn(async () => {}),
    getUser: vi.fn(async () => user()),
    ...overrides,
  } as UserManagerLike
}

describe('console auth', () => {
  it('starts a sign-in that remembers where to continue', async () => {
    const managerLike = manager()
    await new ConsoleAuth(managerLike).signIn('/tenants')

    expect(managerLike.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/tenants' } })
  })

  it('completes the callback and reports the place to return to', async () => {
    const managerLike = manager({
      signinRedirectCallback: vi.fn(async () => user({ state: { returnTo: '/tenants' } })),
    })

    const result = await new ConsoleAuth(managerLike).completeSignIn('http://console.test/console/callback?code=1')

    expect(result.returnTo).toBe('/tenants')
    expect(result.session.user?.displayName).toBe('Ada Lovelace')
    expect(result.session.accessToken).toBe('access-1')
  })

  it('returns the token while it is comfortable', async () => {
    const managerLike = manager()
    const token = await new ConsoleAuth(managerLike).getToken(0)

    expect(token).toBe('access-1')
    expect(managerLike.signinSilent).not.toHaveBeenCalled()
  })

  it('renews a token that is about to expire', async () => {
    const managerLike = manager()
    const token = await new ConsoleAuth(managerLike).getToken(990_000)

    expect(token).toBe('access-1')
    expect(managerLike.signinSilent).toHaveBeenCalledTimes(1)
  })

  it('refuses a token when nobody is signed in', async () => {
    const managerLike = manager({ getUser: vi.fn(async () => null) })

    await expect(new ConsoleAuth(managerLike).getToken()).rejects.toThrow('not signed in')
  })

  it('clears the session before signing out', async () => {
    const managerLike = manager()
    await new ConsoleAuth(managerLike).signOut()

    expect(managerLike.removeUser).toHaveBeenCalled()
    expect(managerLike.signoutRedirect).toHaveBeenCalled()
  })

  it('keeps the callback and logout under the console base', () => {
    expect(CALLBACK_PATH).toBe('/console/callback')
    expect(LOGOUT_PATH).toBe('/console/logout')
  })
})

describe('tenant of a host', () => {
  it('is the first label of a subdomain, platform included', () => {
    expect(tenantCodeOfHost('acme.example.com')).toBe('acme')
    expect(tenantCodeOfHost('acme.localhost')).toBe('acme')
    expect(tenantCodeOfHost('platform.example.com')).toBe('platform')
    expect(tenantCodeOfHost('Acme-Corp.example.com')).toBe('acme-corp')
  })

  it('is nothing for a bare host, an address or a label that is not a tenant code', () => {
    expect(tenantCodeOfHost('localhost')).toBeUndefined()
    expect(tenantCodeOfHost('127.0.0.1')).toBeUndefined()
    expect(tenantCodeOfHost('a.example.com')).toBeUndefined()
    expect(tenantCodeOfHost('-acme.example.com')).toBeUndefined()
    expect(tenantCodeOfHost('')).toBeUndefined()
  })
})
