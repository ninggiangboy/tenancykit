import { isCompatible, type RemoteModule } from '@suite/shell-sdk'
import type { ConsoleRemote } from './contracts'

/** The part of the Module Federation runtime the shell uses. */
export interface FederationRuntime {
  registerRemotes(remotes: Array<{ name: string; entry: string }>, options?: { force?: boolean }): void
  loadRemote(id: string): Promise<unknown>
  registerPlugins(plugins: unknown[]): void
}

export interface RemoteLoaderOptions {
  runtime: FederationRuntime
  /** Where the page is; a relative entry path is resolved against it, and every entry must stay same-origin. */
  origin?: string
}

export interface LoadedRemote {
  module: RemoteModule
}

export class RemoteRefused extends Error {
  constructor(
    readonly remote: ConsoleRemote,
    readonly reason: 'incompatible' | 'unverified' | 'broken',
    message: string,
  ) {
    super(message)
    this.name = 'RemoteRefused'
  }
}

/**
 * Loads a remote only after its manifest was verified with the registry's hash. The Module Federation runtime fetches
 * the manifest itself, so a fetch plugin attaches the hash to that very request: the bytes that are verified are the
 * bytes that are parsed. Chunk bodies are outside that hash, which is why every entry must be same-origin and every
 * version directory immutable.
 */
export function createRemoteLoader({ runtime, origin = globalThis.location?.origin ?? '' }: RemoteLoaderOptions) {
  const integrityByUrl = new Map<string, string>()

  runtime.registerPlugins([
    {
      name: 'registry-integrity',
      fetch(url: string | URL | Request, init: RequestInit) {
        const href = new URL(typeof url === 'string' || url instanceof URL ? url : url.url, origin).href
        const integrity = integrityByUrl.get(href)
        if (integrity) {
          return fetcher(href, { ...init, integrity, cache: 'no-cache' })
        }
      },
    },
  ])

  const loaded = new Map<string, Promise<RemoteModule>>()

  return {
    /** @throws RemoteRefused when the major differs, the manifest does not match, or the module throws while loading */
    load(remote: ConsoleRemote): Promise<RemoteModule> {
      if (!isCompatible(remote.sdkMajor)) {
        return Promise.reject(
          new RemoteRefused(
            remote,
            'incompatible',
            `This remote was built for shell-sdk ${remote.sdkMajor}; this shell speaks sdk ${1}. Ask for a new build.`,
          ),
        )
      }
      const entry = new URL(remote.entryPath, origin).href
      if (new URL(entry).origin !== new URL(origin).origin) {
        return Promise.reject(new RemoteRefused(remote, 'unverified', 'The registry entry is not on this origin.'))
      }
      const key = `${remote.name}@${remote.version}`
      let pending = loaded.get(key)
      if (!pending) {
        pending = (async () => {
          integrityByUrl.set(entry, remote.integrity)
          runtime.registerRemotes([{ name: remote.name, entry }], { force: true })
          const module = (await runtime.loadRemote(`${remote.name}/app`)) as { default?: RemoteModule } | RemoteModule
          const resolved = 'default' in module && module.default ? module.default : (module as RemoteModule)
          if (!resolved?.Root) {
            throw new RemoteRefused(remote, 'broken', 'The remote export has no Root component.')
          }
          return resolved as RemoteModule
        })().catch((error: unknown) => {
          loaded.delete(key)
          throw error instanceof RemoteRefused ? error : new RemoteRefused(remote, 'broken', String(error))
        })
        loaded.set(key, pending)
      }
      return pending
    },
  }
}

/** Indirection so the integrity-carrying fetch is the page's own, which is what a browser verifies. */
function fetcher(url: string, init: RequestInit): Promise<Response> {
  return globalThis.fetch(url, init)
}
