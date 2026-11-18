import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConsoleRemote } from './contracts'
import { createRemoteLoader, type FederationRuntime, RemoteRefused } from './remote-loader'

const Root = () => null

const remote = (overrides: Partial<ConsoleRemote> = {}): ConsoleRemote => ({
  name: 'billing',
  title: 'Billing',
  appCode: 'billing',
  navOrder: 20,
  version: '1.0.0',
  entryPath: '/console/remotes/billing/1.0.0/mf-manifest.json',
  integrity: 'sha384-abc',
  sdkMajor: 1,
  ...overrides,
})

function runtime(module: unknown = { default: { name: 'billing', sdkMajor: 1, Root } }) {
  const registered: Array<{ name: string; entry: string }> = []
  const plugins: unknown[] = []
  const fake: FederationRuntime & { registered: typeof registered; plugins: typeof plugins } = {
    registered,
    plugins,
    registerPlugins: (next) => {
      plugins.push(...next)
    },
    registerRemotes: (next) => {
      registered.push(...next)
    },
    loadRemote: vi.fn(async () => module),
  }
  return fake
}

afterEach(() => vi.unstubAllGlobals())

describe('the remote loader', () => {
  it('refuses a remote built for another shell-sdk major before touching the network', async () => {
    const federation = runtime()
    const loader = createRemoteLoader({ runtime: federation, origin: 'http://console.test' })

    await expect(loader.load(remote({ sdkMajor: 2 }))).rejects.toMatchObject({ reason: 'incompatible' })
    expect(federation.registered).toHaveLength(0)
  })

  it('refuses an entry path that is not on this origin', async () => {
    const loader = createRemoteLoader({ runtime: runtime(), origin: 'http://console.test' })

    await expect(loader.load(remote({ entryPath: 'https://elsewhere.test/mf-manifest.json' }))).rejects.toMatchObject({
      reason: 'unverified',
    })
  })

  it('registers the remote and loads its ./app export', async () => {
    const federation = runtime()
    const loader = createRemoteLoader({ runtime: federation, origin: 'http://console.test' })

    const module = await loader.load(remote())

    expect(module.name).toBe('billing')
    expect(federation.registered).toEqual([
      { name: 'billing', entry: 'http://console.test/console/remotes/billing/1.0.0/mf-manifest.json' },
    ])
    expect(federation.loadRemote).toHaveBeenCalledWith('billing/app')
  })

  it('attaches the registry hash to the very request that fetches the manifest', async () => {
    const sent = vi.fn(async () => new Response('{}'))
    vi.stubGlobal('fetch', sent)
    const federation = runtime()
    const loader = createRemoteLoader({ runtime: federation, origin: 'http://console.test' })
    await loader.load(remote())

    const plugin = federation.plugins[0] as { fetch: (url: string, init: RequestInit) => Promise<Response> | undefined }
    await plugin.fetch('http://console.test/console/remotes/billing/1.0.0/mf-manifest.json', {})

    expect(sent).toHaveBeenCalledWith(
      'http://console.test/console/remotes/billing/1.0.0/mf-manifest.json',
      expect.objectContaining({ integrity: 'sha384-abc', cache: 'no-cache' }),
    )
  })

  it('refuses a module that exports no Root, and caches a working load', async () => {
    const broken = runtime({ default: { name: 'billing', sdkMajor: 1 } })
    await expect(
      createRemoteLoader({ runtime: broken, origin: 'http://console.test' }).load(remote()),
    ).rejects.toBeInstanceOf(RemoteRefused)

    const working = runtime()
    const loader = createRemoteLoader({ runtime: working, origin: 'http://console.test' })
    await loader.load(remote())
    await loader.load(remote())
    expect(working.loadRemote).toHaveBeenCalledTimes(1)
  })
})
