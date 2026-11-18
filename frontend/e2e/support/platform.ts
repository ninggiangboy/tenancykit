import { env } from './env'

export interface Answer<T> {
  status: number
  body: T
}

export interface CallOptions {
  method?: string
  token?: string
  /** The host whose tenant scope the call wants; the Platform host by default. */
  host?: string
  body?: unknown
  headers?: Record<string, string>
}

/** One call through Caddy. The hostname, not a header, is what selects a tenant, so it lives in the URL. */
export async function call<T = unknown>(
  path: string,
  { method = 'GET', token, host = env.platformHost, body, headers = {} }: CallOptions = {},
): Promise<Answer<T>> {
  const response = await fetch(`${env.originFor(host)}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const type = response.headers.get('content-type') ?? ''
  const parsed = text === '' ? null : type.includes('json') ? JSON.parse(text) : text
  return { status: response.status, body: parsed as T }
}

/** Fails with the server's own sentence, so a broken fixture says why instead of timing out later. */
export function requireOk<T>(answer: Answer<T>, what: string): T {
  if (answer.status < 200 || answer.status >= 300) {
    throw new Error(`${what} failed with ${answer.status}: ${JSON.stringify(answer.body)}`)
  }
  return answer.body
}

export interface TenantResponse {
  id: string
  code: string
  name: string
  status: string
  version: number
}

export interface AppEntitlementResponse {
  appCode: string
  enabled: boolean
  version: number
}

export interface RoleResponse {
  id: string
  tenantId: string | null
  scope: 'PLATFORM' | 'TENANT'
  name: string
  system: boolean
  permissions: string[]
  version: number
}

export interface RemoteVersionResponse {
  version: string
  entryPath: string
  integrity: string
  sdkMajor: number
  registeredAt: string
}

export interface RemoteResponse {
  name: string
  appCode: string
  title: string
  navOrder: number
  audiences: string[]
  enabled: boolean
  activeVersion: string | null
  version: number
  versions: RemoteVersionResponse[]
}

export interface ConsoleRemoteResponse {
  name: string
  title: string
  appCode: string
  navOrder: number
  version: string
  entryPath: string
  integrity: string
  sdkMajor: number
}

export interface MeResponse {
  id: string
  tenantId: string | null
  scope: 'PLATFORM' | 'TENANT'
  email: string
  displayName: string
  permissions: string[]
}

export interface Page<T> {
  data: T[]
  page?: { limit?: number; offset?: number | null; total?: number | null } | null
}

export function listParams(params: Record<string, string>): string {
  return new URLSearchParams({ ...params, limit: '100' }).toString()
}

export async function findTenant(token: string, code: string): Promise<TenantResponse | null> {
  const query = new URLSearchParams({ 'filter[code][eq]': code, limit: '1' })
  const answer = await call<Page<TenantResponse>>(`/platform/api/v1/tenants?${query}`, { token })
  return requireOk(answer, `looking up tenant ${code}`).data[0] ?? null
}

export async function provisionTenant(
  token: string,
  body: { code: string; name: string; adminEmail: string; adminTemporaryPassword: string },
): Promise<string> {
  const answer = await call<{ tenantId: string }>('/platform/api/v1/tenants', { method: 'POST', token, body })
  return requireOk(answer, `creating tenant ${body.code}`).tenantId
}

export async function tenantApps(token: string, tenantId: string): Promise<AppEntitlementResponse[]> {
  const answer = await call<AppEntitlementResponse[]>(`/platform/api/v1/tenants/${tenantId}/apps`, { token })
  return requireOk(answer, `reading the apps of tenant ${tenantId}`)
}

export async function setTenantApp(token: string, tenantId: string, appCode: string, enabled: boolean): Promise<void> {
  const answer = await call(`/platform/api/v1/tenants/${tenantId}/apps/${appCode}`, {
    method: 'PUT',
    token,
    body: { enabled },
  })
  requireOk(answer, `turning ${appCode} ${enabled ? 'on' : 'off'} for tenant ${tenantId}`)
}

export async function listRoles(token: string, tenantId: string): Promise<RoleResponse[]> {
  const query = new URLSearchParams({ 'filter[tenant_id][eq]': tenantId, limit: '100' })
  const answer = await call<Page<RoleResponse>>(`/platform/api/v1/roles?${query}`, { token })
  return requireOk(answer, `listing the roles of tenant ${tenantId}`).data
}

export async function replaceRolePermissions(
  token: string,
  role: RoleResponse,
  permissions: string[],
): Promise<RoleResponse> {
  const answer = await call<RoleResponse>(`/platform/api/v1/roles/${role.id}/permissions`, {
    method: 'PUT',
    token,
    body: { permissions },
    headers: { 'If-Match': `"${role.version}"` },
  })
  return requireOk(answer, `replacing the permissions of role ${role.name}`)
}

export async function createUser(
  token: string,
  body: { tenantCode: string; email: string; displayName: string; temporaryPassword: string },
): Promise<{ id: string }> {
  const answer = await call<{ id: string }>('/platform/api/v1/users', { method: 'POST', token, body })
  return requireOk(answer, `creating user ${body.email}`)
}

export async function listRemotes(token: string): Promise<RemoteResponse[]> {
  const answer = await call<Page<RemoteResponse>>('/platform/api/v1/ui-remotes?limit=100', { token })
  return requireOk(answer, 'listing the UI remotes').data
}

export async function findRemote(token: string, name: string): Promise<RemoteResponse> {
  const remote = (await listRemotes(token)).find((entry) => entry.name === name)
  if (!remote) throw new Error(`the registry has no remote named ${name}`)
  return remote
}

export async function setRemoteState(
  token: string,
  remote: RemoteResponse,
  body: { enabled?: boolean; activeVersion?: string },
): Promise<RemoteResponse> {
  const answer = await call<RemoteResponse>(`/platform/api/v1/ui-remotes/${remote.name}`, {
    method: 'PUT',
    token,
    body,
    headers: { 'If-Match': `"${remote.version}"` },
  })
  return requireOk(answer, `changing the state of remote ${remote.name}`)
}

export async function registerVersion(
  token: string,
  name: string,
  body: {
    version: string
    entryPath: string
    integrity: string
    sdkMajor: number
    appCode?: string
    title?: string
    audiences?: string[]
    navOrder?: number
  },
): Promise<Answer<RemoteVersionResponse>> {
  return call<RemoteVersionResponse>(`/platform/api/v1/ui-remotes/${name}/versions`, {
    method: 'POST',
    token,
    body,
  })
}

export async function consoleFeed(token: string, host = env.platformHost): Promise<ConsoleRemoteResponse[]> {
  const answer = await call<ConsoleRemoteResponse[]>('/platform/api/v1/console/remotes', { token, host })
  return requireOk(answer, 'reading the console feed')
}

export async function me(token: string, host = env.platformHost): Promise<MeResponse> {
  const answer = await call<MeResponse>('/platform/api/v1/me', { token, host })
  return requireOk(answer, 'reading /me')
}
