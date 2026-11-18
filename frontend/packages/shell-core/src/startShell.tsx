import { loadRemote, registerPlugins, registerRemotes } from '@module-federation/runtime'
import { Button, EmptyState, ErrorState } from '@suite/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { bootShell } from './boot'
import { loadConsoleConfig } from './config'
import { BootRefused, type ShellDefinition } from './contracts'
import { createShellI18n } from './i18n'
import { createRemoteLoader, type FederationRuntime } from './remote-loader'
import { ShellApp } from './ShellApp'
import { preferredLocale } from './theme'

/** The auth surface the shell needs; `@suite/auth`'s ConsoleAuth satisfies it. */
export interface ShellAuth {
  signIn(returnTo?: string): Promise<void>
  completeSignIn(url?: string): Promise<{ returnTo?: string }>
  signOut(): Promise<void>
  getToken(): Promise<string>
}

export interface StartShellOptions {
  definition: ShellDefinition
  auth: ShellAuth
  /** The Module Federation runtime; a test may pass a stand-in. */
  federation?: FederationRuntime
  root?: HTMLElement | null
  /** The runtime configuration, when the entry already loaded it to build the auth client. */
  config?: Awaited<ReturnType<typeof loadConsoleConfig>>
}

const CONSOLE_BASE = '/console'

/**
 * The whole shell: read the runtime configuration, finish a sign-in callback, then boot. A caller from the other
 * audience gets a refusal page, an ended session goes back through Keycloak, and nothing renders half-loaded.
 */
export async function startShell({ definition, auth, federation, root, config }: StartShellOptions): Promise<void> {
  const i18n = createShellI18n(preferredLocale())
  const t = (key: string) => String(i18n.t(key))
  const mount = root ?? document.getElementById('root')
  if (!mount) {
    throw new Error('the shell page has no root element')
  }

  await (config ? Promise.resolve(config) : loadConsoleConfig())
  const path = globalThis.location.pathname
  // The sign-in round trip returns to the whole URL, not only its path: a sheet, a filter and a sort are all in the
  // query string, and a reload that dropped them would close what the person was looking at.
  const returnTo = `${path}${globalThis.location.search}`

  if (path.startsWith(`${CONSOLE_BASE}/callback`)) {
    const { returnTo } = await auth.completeSignIn()
    globalThis.history.replaceState({}, '', returnTo ?? `${CONSOLE_BASE}/`)
  } else if (path.startsWith(`${CONSOLE_BASE}/logout`)) {
    await auth.signOut()
    return
  }

  try {
    // A shell with no session asks Keycloak before it asks Platform; otherwise the first API call's own refusal would
    // look like an outage instead of a sign-in.
    await auth.getToken()
  } catch {
    await auth.signIn(returnTo)
    return
  }

  try {
    const { me, remotes } = await bootShell({ definition, getToken: auth.getToken })
    const runtime = (federation ?? { loadRemote, registerPlugins, registerRemotes }) as FederationRuntime
    const loader = createRemoteLoader({ runtime })
    createRoot(mount).render(
      <StrictMode>
        <ShellApp
          definition={definition}
          me={me}
          remotes={remotes}
          loader={loader}
          getToken={auth.getToken}
          onSignOut={() => void auth.signOut()}
        />
      </StrictMode>,
    )
  } catch (error) {
    // A failed boot is logged with its cause; the page shows the sentence, the console keeps the reason.
    console.error('the console could not start', error)
    if (error instanceof BootRefused && error.kind === 'expired') {
      await auth.signIn(returnTo)
      return
    }
    const refusal = error instanceof BootRefused ? error : new BootRefused('unavailable', t('shell.unavailableHint'))
    const title =
      refusal.kind === 'forbidden'
        ? t('shell.forbidden')
        : refusal.kind === 'expired'
          ? t('shell.expired')
          : t('shell.unavailable')
    createRoot(mount).render(
      <StrictMode>
        <div className="flex min-h-dvh items-center justify-center bg-background">
          {refusal.kind === 'unavailable' ? (
            <ErrorState title={title} description={refusal.message} onRetry={() => globalThis.location.reload()} />
          ) : (
            <EmptyState
              icon={refusal.kind === 'forbidden' ? 'ban' : 'clock'}
              title={title}
              description={refusal.message}
            >
              {refusal.kind === 'expired' && (
                <Button onClick={() => void auth.signIn(returnTo)}>{t('shell.signIn')}</Button>
              )}
            </EmptyState>
          )}
        </div>
      </StrictMode>,
    )
  }
}
