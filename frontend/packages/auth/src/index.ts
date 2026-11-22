import { InMemoryWebStorage, type User, UserManager, WebStorageStateStore } from 'oidc-client-ts'

/** The shell's entry points: sign-in, the callback, a token for the remotes, and sign-out. */
export const CONSOLE_BASE = '/console'
export const CALLBACK_PATH = `${CONSOLE_BASE}/callback`
export const LOGOUT_PATH = `${CONSOLE_BASE}/logout`

/** What the runtime config file gives the shell: everything a public PKCE client needs, and nothing secret. */
export interface ConsoleAuthConfig {
  authority: string
  clientId: string
  scope?: string
}

/** The part of `UserManager` this package uses, so a test can stand in for it. */
export interface UserManagerLike {
  signinRedirect(args?: { state?: Record<string, unknown> }): Promise<void>
  signinRedirectCallback(url?: string): Promise<User>
  signinSilent(): Promise<User | null>
  signoutRedirect(args?: Record<string, unknown>): Promise<void>
  removeUser(): Promise<void>
  getUser(): Promise<User | null>
}

export interface ConsoleSession {
  accessToken: string
  /** Epoch millis; the shell refreshes before this. */
  expiresAt: number
  user: { id: string; email: string; displayName: string } | null
}

export class AuthError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthError'
  }
}

/** The format Platform enforces for a tenant code, which is also the first label of the tenant's host. */
const TENANT_CODE = /^[a-z][a-z0-9-]{1,38}[a-z0-9]$/
const IPV4 = /^\d+(\.\d+){3}$/

/**
 * The tenant a host belongs to: its first label, when the host has a subdomain (`acme.example.com`, and
 * `platform.example.com` for the platform). A bare host such as `localhost` or an IP address names no tenant, and a
 * sign-in from it takes the full `tenant:email` username, as it always did.
 */
export function tenantCodeOfHost(hostname: string): string | undefined {
  const labels = hostname.toLowerCase().split('.')
  if (labels.length < 2 || IPV4.test(hostname)) {
    return undefined
  }
  return TENANT_CODE.test(labels[0] ?? '') ? labels[0] : undefined
}

/** The origin's tenant as the extra sign-in parameter Keycloak's tenant username form reads. */
function tenantParams(origin: string): Record<string, string> {
  try {
    const tenant = tenantCodeOfHost(new URL(origin).hostname)
    return tenant ? { tenant } : {}
  } catch {
    return {}
  }
}

/** Refresh this long before expiry, so a request started at the edge never carries a dead token. */
const REFRESH_MARGIN_MS = 30_000

/**
 * One Keycloak client per console, tokens held in memory: a script that a remote pulls in cannot read a stored token,
 * and a reload signs in again through the silent flow. Only the shell imports this.
 */
export class ConsoleAuth {
  private readonly manager: UserManagerLike

  constructor(manager: UserManagerLike) {
    this.manager = manager
  }

  /** Builds the real manager from the runtime configuration; PKCE and the in-memory store are the whole setup. */
  static fromConfig(config: ConsoleAuthConfig, origin = globalThis.location?.origin ?? ''): ConsoleAuth {
    const manager = new UserManager({
      authority: config.authority,
      client_id: config.clientId,
      redirect_uri: `${origin}${CALLBACK_PATH}`,
      post_logout_redirect_uri: `${origin}${LOGOUT_PATH}`,
      response_type: 'code',
      scope: config.scope ?? 'openid profile email',
      automaticSilentRenew: false,
      // The host already says which tenant this is, so the person types only an email on Keycloak's form.
      extraQueryParams: tenantParams(origin),
      userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    })
    return new ConsoleAuth(manager)
  }

  signIn = async (returnTo?: string): Promise<void> => {
    await this.manager.signinRedirect(returnTo ? { state: { returnTo } } : undefined)
  }

  /** @return where the sign-in should continue, when the caller asked for a place before signing in */
  completeSignIn = async (url = globalThis.location?.href): Promise<{ session: ConsoleSession; returnTo?: string }> => {
    const user = await this.manager.signinRedirectCallback(url)
    return { session: toSession(user), returnTo: returnToOf(user) }
  }

  signOut = async (): Promise<void> => {
    await this.manager.removeUser()
    await this.manager.signoutRedirect()
  }

  currentSession = async (): Promise<ConsoleSession | null> => {
    const user = await this.manager.getUser()
    return user?.expired === false ? toSession(user) : null
  }

  /**
   * A token a remote can use right now. A token near expiry triggers one silent refresh, so a request started at the
   * edge of the lifetime still carries a valid one.
   */
  /** Bound on purpose: the shell hands it to the clients as a plain function. */
  getToken = async (now = Date.now()): Promise<string> => {
    const user = await this.manager.getUser()
    if (!user?.access_token) {
      throw new AuthError('not signed in')
    }
    if (user.expires_at !== undefined && user.expires_at * 1000 - now < REFRESH_MARGIN_MS) {
      const refreshed = await this.manager.signinSilent()
      if (!refreshed?.access_token) {
        throw new AuthError('the session could not be renewed')
      }
      return refreshed.access_token
    }
    return user.access_token
  }
}

function toSession(user: User): ConsoleSession {
  const profile = user.profile as { sub?: string; email?: string; name?: string; preferred_username?: string }
  return {
    accessToken: user.access_token,
    expiresAt: (user.expires_at ?? 0) * 1000,
    user: profile.sub
      ? {
          id: profile.sub,
          email: profile.email ?? '',
          displayName: profile.name ?? profile.preferred_username ?? profile.email ?? '',
        }
      : null,
  }
}

function returnToOf(user: User): string | undefined {
  const state = user.state as { returnTo?: string } | undefined
  return state?.returnTo
}
