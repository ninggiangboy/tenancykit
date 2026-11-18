import { env } from './env'

/** The token endpoint Keycloak exposes for one realm. */
export function tokenEndpoint(): string {
  return `${env.keycloak}/realms/${env.realm}/protocol/openid-connect/token`
}

async function form(url: string, body: Record<string, string>): Promise<Record<string, unknown>> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(body),
  })
  const text = await response.text()
  if (!response.ok) {
    throw new Error(`Keycloak answered ${response.status} to ${url}: ${text}`)
  }
  return JSON.parse(text) as Record<string, unknown>
}

let adminToken: { value: string; expiresAt: number } | null = null

/** A token for Keycloak's Admin API, from the platform-admin service account. */
export async function keycloakAdminToken(): Promise<string> {
  if (adminToken && adminToken.expiresAt > Date.now() + 15_000) {
    return adminToken.value
  }
  const answer = await form(tokenEndpoint(), {
    grant_type: 'client_credentials',
    client_id: env.keycloakAdmin.id,
    client_secret: env.keycloakAdmin.secret,
  })
  const value = String(answer.access_token ?? '')
  if (!value) throw new Error('Keycloak returned no admin token')
  adminToken = { value, expiresAt: Date.now() + Number(answer.expires_in ?? 60) * 1000 }
  return value
}

async function admin(path: string, init: RequestInit = {}): Promise<Response> {
  const token = await keycloakAdminToken()
  return fetch(`${env.keycloak}/admin/realms/${env.realm}${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
}

/**
 * Makes an account Keycloak created with a temporary password usable by the tests: a permanent password and no
 * required action, exactly what an operator does to a test account. Only the accounts a test created are touched.
 */
export async function makeLoginable(username: string, password: string): Promise<void> {
  const found = await admin(`/users?exact=true&username=${encodeURIComponent(username)}`)
  if (!found.ok) throw new Error(`Keycloak refused to look up ${username}: ${found.status}`)
  const users = (await found.json()) as Array<{ id?: string }>
  const id = users[0]?.id
  if (!id) throw new Error(`Keycloak has no account named ${username}`)

  const cleared = await admin(`/users/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ requiredActions: [], enabled: true }),
  })
  if (!cleared.ok) throw new Error(`Keycloak refused to enable ${username}: ${cleared.status}`)

  const reset = await admin(`/users/${id}/reset-password`, {
    method: 'PUT',
    body: JSON.stringify({ type: 'password', value: password, temporary: false }),
  })
  if (!reset.ok) throw new Error(`Keycloak refused to set the password of ${username}: ${reset.status}`)
}

/** A direct grant for an account the tests provisioned; the confidential client is the suite's own smoke client. */
export async function directGrant(username: string, password: string): Promise<string> {
  return (await grant(username, password)).token
}

async function grant(username: string, password: string): Promise<{ token: string; expiresAt: number }> {
  const answer = await form(tokenEndpoint(), {
    grant_type: 'password',
    client_id: env.smokeClient.id,
    client_secret: env.smokeClient.secret,
    username,
    password,
  })
  const token = String(answer.access_token ?? '')
  if (!token) throw new Error(`Keycloak returned no token for ${username}`)
  return { token, expiresAt: Date.now() + Number(answer.expires_in ?? 300) * 1000 }
}

const grants = new Map<string, { token: string; expiresAt: number }>()

/**
 * One direct grant per account, renewed before it expires: the suite runs longer than a Keycloak access token lives,
 * and the Platform answers 401 to a stale one.
 */
export async function accountToken(username: string, password: string): Promise<string> {
  const cached = grants.get(username)
  if (cached && cached.expiresAt > Date.now() + 30_000) return cached.token
  const granted = await grant(username, password)
  grants.set(username, granted)
  return granted.token
}

/** The redirect URIs the console client accepts, which is what a sign-in from a test host needs. */
export async function consoleRedirectUris(clientId: string): Promise<string[]> {
  const found = await admin(`/clients?clientId=${encodeURIComponent(clientId)}`)
  if (!found.ok) throw new Error(`Keycloak refused to read the client ${clientId}: ${found.status}`)
  const clients = (await found.json()) as Array<{ redirectUris?: string[] }>
  return clients[0]?.redirectUris ?? []
}
