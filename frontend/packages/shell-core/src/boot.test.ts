import { afterEach, describe, expect, it, vi } from 'vitest'
import { bootShell } from './boot'
import { BootRefused, refusalFor } from './contracts'

const PLATFORM = { audience: 'PLATFORM' as const, title: 'Platform' }
const TENANT = { audience: 'TENANT' as const, title: 'Console' }

const me = (overrides: Record<string, unknown> = {}) => ({
  id: 'u1',
  tenantId: 't1',
  scope: 'TENANT',
  email: 'ada@acme.test',
  displayName: 'Ada',
  status: 'ACTIVE',
  permissions: ['billing:invoice:read'],
  ...overrides,
})

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function route(handlers: { me?: Response; feed?: Response }) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url)
      if (url.pathname.endsWith('/api/v1/me')) return handlers.me ?? respond(200, me())
      if (url.pathname.endsWith('/api/v1/console/remotes')) return handlers.feed ?? respond(200, [])
      return respond(404, {})
    }),
  )
}

const options = { resolveBaseUrl: () => 'http://console.test/platform' } as const

afterEach(() => vi.unstubAllGlobals())

describe('bootShell', () => {
  it('sorts the feed by navigation order', async () => {
    route({
      feed: respond(200, [
        {
          name: 'billing',
          title: 'Billing',
          appCode: 'billing',
          navOrder: 20,
          version: '1',
          entryPath: '/b',
          integrity: 'i',
          sdkMajor: 1,
        },
        {
          name: 'platform',
          title: 'Platform',
          appCode: 'platform',
          navOrder: 10,
          version: '1',
          entryPath: '/p',
          integrity: 'i',
          sdkMajor: 1,
        },
      ]),
    })

    const booted = await bootShell({ definition: TENANT, getToken: async () => 'token', ...options })

    expect(booted.remotes.map((remote) => remote.name)).toEqual(['platform', 'billing'])
    expect(booted.me.permissions).toContain('billing:invoice:read')
  })

  it('refuses a Platform user on a tenant shell, and the other way round', async () => {
    route({ me: respond(200, me({ scope: 'PLATFORM', tenantId: null })) })

    await expect(bootShell({ definition: TENANT, getToken: async () => 'token', ...options })).rejects.toMatchObject({
      kind: 'forbidden',
    })

    route({ me: respond(200, me()) })
    await expect(bootShell({ definition: PLATFORM, getToken: async () => 'token', ...options })).rejects.toMatchObject({
      kind: 'forbidden',
    })
  })

  it('treats an ended session as expired, so the shell can sign in again', async () => {
    route({ me: respond(401, { detail: 'unauthorized' }) })

    await expect(bootShell({ definition: TENANT, getToken: async () => 'token', ...options })).rejects.toMatchObject({
      kind: 'expired',
    })
  })

  it('treats a forbidden account as a refusal, not a re-login', async () => {
    route({ me: respond(403, { detail: 'the user is not active here' }) })

    const error = await bootShell({ definition: TENANT, getToken: async () => 'token', ...options }).catch(
      (thrown) => thrown,
    )

    expect(error).toBeInstanceOf(BootRefused)
    expect(error.kind).toBe('forbidden')
    expect(error.message).toContain('not active here')
  })

  it('reports an unavailable Platform when the feed fails', async () => {
    route({ feed: respond(500, { detail: 'something broke' }) })

    await expect(bootShell({ definition: TENANT, getToken: async () => 'token', ...options })).rejects.toMatchObject({
      kind: 'unavailable',
    })
  })

  it('refuses an account that is not active', () => {
    const refusal = refusalFor(me({ status: 'DISABLED' }) as never, TENANT)

    expect(refusal?.kind).toBe('forbidden')
  })
})
