import { createApiClients } from '@suite/api-client'
import { feed as consoleFeed, me as meQuery } from '@suite/api-client/platform'
import { type BootOptions, BootRefused, type ConsoleRemote, type MeResponse, refusalFor } from './contracts'

export interface BootedShell {
  me: MeResponse
  remotes: ConsoleRemote[]
}

/**
 * The boot every shell shares: ask who the caller is, refuse a caller from the other audience, then read the remotes
 * this caller may mount. Nothing renders until all three answers are in, so a refusal is a page rather than a
 * half-loaded console.
 */
export async function bootShell(options: BootOptions): Promise<BootedShell> {
  const clients = createApiClients({ getToken: options.getToken, resolveBaseUrl: options.resolveBaseUrl })

  const me = await meQuery({ client: clients.platform }).then((result) => {
    if (result.error !== undefined) {
      throw refusalFrom(result.response?.status, result.error, 'the console could not read your account')
    }
    return result.data as MeResponse
  })

  const refusal = refusalFor(me, options.definition)
  if (refusal) {
    throw refusal
  }

  const remotes = await consoleFeed({ client: clients.platform }).then((result) => {
    if (result.error !== undefined) {
      throw refusalFrom(result.response?.status, result.error, 'the console could not read the remotes')
    }
    return (result.data as ConsoleRemote[]).slice().sort(compareRemotes)
  })

  return { me, remotes }
}

function compareRemotes(left: ConsoleRemote, right: ConsoleRemote): number {
  return left.navOrder - right.navOrder || left.name.localeCompare(right.name)
}

/** The generated client returns the response with the body it could not use; the status decides what the shell shows. */
function refusalFrom(status: number | undefined, error: unknown, fallback: string): BootRefused {
  const detail = typeof (error as { detail?: unknown })?.detail === 'string' ? (error as { detail: string }).detail : ''
  if (status === 401) {
    return new BootRefused('expired', 'Your session ended. Sign in again.')
  }
  if (status === 403) {
    return new BootRefused('forbidden', detail || 'You may not use this console.')
  }
  return new BootRefused('unavailable', detail || fallback)
}
