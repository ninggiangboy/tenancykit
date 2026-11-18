import type { ShellContext } from '@suite/shell-sdk'

/** A remote as the registry feed returns it, which is everything the shell needs to verify and mount it. */
export interface ConsoleRemote {
  name: string
  title: string
  appCode: string
  navOrder: number
  version: string
  entryPath: string
  integrity: string
  sdkMajor: number
}

/** The signed-in user as `/me` reports them. */
export interface MeResponse {
  id: string
  tenantId: string | null
  scope: 'PLATFORM' | 'TENANT'
  email: string
  displayName: string
  status: string
  permissions: string[]
}

export class BootRefused extends Error {
  constructor(
    /** What the shell should show: a refusal page, a session that ended, or an unavailable service. */
    readonly kind: 'forbidden' | 'expired' | 'unavailable',
    message: string,
  ) {
    super(message)
    this.name = 'BootRefused'
  }
}

export interface ShellDefinition {
  audience: 'PLATFORM' | 'TENANT'
  title: string
}

export interface BootOptions {
  definition: ShellDefinition
  /** Always resolves to a token the shell holds; a missing session throws, which the shell shows as an expired one. */
  getToken: () => Promise<string>
  /** The tenant's hostname label, used for the switcher when `/me` names only the tenant id. */
  tenantCodeFromHost?: () => string
  /** Where the backends live when they are not on the page's own origin; a test answers with an absolute base. */
  resolveBaseUrl?: (api: 'platform' | 'billing') => string
}

export interface BootedShell {
  ctx: ShellContext
  remotes: ConsoleRemote[]
  me: MeResponse
}

/** The one refusal the shell shows instead of a half-loaded console. */
export function refusalFor(me: MeResponse, definition: ShellDefinition): BootRefused | null {
  if (me.scope !== definition.audience) {
    return new BootRefused(
      'forbidden',
      definition.audience === 'PLATFORM'
        ? 'This is the Platform console. Your account belongs to a tenant; open the console on your own host.'
        : 'This is a tenant console. Your account is a Platform administrator; open the Platform console instead.',
    )
  }
  if (me.status !== 'ACTIVE') {
    return new BootRefused('forbidden', 'Your account is not active. Ask an administrator to enable it.')
  }
  return null
}

/** The tenant a tenant-scope user works in, from the host: the first label of `<code>.<base-domain>`. */
export function tenantFromHost(hostname: string): { code: string; name: string } {
  const code = hostname.split('.')[0] ?? hostname
  return { code, name: code }
}
