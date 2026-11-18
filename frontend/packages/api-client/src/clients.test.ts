import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApiClients } from './clients'
import { me, registerVersion } from './generated/platform'

const seen: Request[] = []

const absolute = (api: 'platform' | 'billing') =>
  api === 'platform' ? 'http://console.test/platform' : 'http://console.test'

function mockFetch(status = 200, body: unknown = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      seen.push(request.clone())
      return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
    }),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  seen.length = 0
})

describe('api clients', () => {
  it('sends the shell token and a trace id on every request', async () => {
    mockFetch()
    const clients = createApiClients({ getToken: async () => 'token-1', resolveBaseUrl: absolute })

    await me({ client: clients.platform })

    expect(seen[0]?.headers.get('authorization')).toBe('Bearer token-1')
    expect(seen[0]?.headers.get('x-request-id')).toBeTruthy()
    expect(seen[0]?.url).toContain('/api/v1/me')
  })

  it('adds an idempotency key to a mutation and not to a read', async () => {
    mockFetch(201, { version: '1.0.0' })
    const clients = createApiClients({
      getToken: async () => 'token-1',
      requestId: () => 'trace-1',
      resolveBaseUrl: absolute,
    })

    await registerVersion({
      client: clients.platform,
      path: { name: 'billing' },
      body: {
        version: '1.0.0',
        entryPath: '/console/remotes/billing/1.0.0/mf-manifest.json',
        integrity: 'x',
        sdkMajor: 1,
      },
    })
    await me({ client: clients.platform })

    expect(seen[0]?.headers.get('idempotency-key')).toBeTruthy()
    expect(seen[0]?.headers.get('x-request-id')).toBe('trace-1')
    expect(seen[1]?.headers.get('idempotency-key')).toBeNull()
  })

  it('keeps Billing on the root and Platform behind its prefix', () => {
    const clients = createApiClients({ getToken: async () => '' })

    expect(clients.platform.getConfig().baseUrl).toBe('/platform')
    expect(clients.billing.getConfig().baseUrl).toBe('')
  })
})
