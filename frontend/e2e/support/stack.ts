import { env, keycloakUsername, uniqueCode } from './env'
import { accountToken, consoleRedirectUris, directGrant, makeLoginable } from './keycloak'
import {
  call,
  findRemote,
  findTenant,
  type MeResponse,
  me,
  provisionTenant,
  type RemoteResponse,
  requireOk,
  setRemoteState,
  setTenantApp,
} from './platform'

const TENANT_ADMIN_PASSWORD = 'Temporary-1'

export interface TenantFixture {
  code: string
  host: string
  tenantId: string
  adminEmail: string
  adminPassword: string
  /** A suite token for the tenant's administrator, for the API side of a test. */
  token: string
}

let platformToken: Promise<string> | null = null

export function platformAdminToken(): Promise<string> {
  platformToken ??= accountToken(keycloakUsername('platform', env.platformAdmin.email), env.platformAdmin.password)
  return platformToken
}

/**
 * A tenant with an administrator the tests can sign in as, and its own host. Each caller gets a fresh code, so a
 * test never depends on another one's records and a rerun never collides.
 */
export async function provisionTenantFixture(
  options: { billing?: boolean; platform?: boolean; prefix?: string } = {},
): Promise<TenantFixture> {
  const token = await platformAdminToken()
  const code = uniqueCode(options.prefix ?? 'e2e')
  const adminEmail = `admin@${code}.test`

  const existing = await findTenant(token, code)
  const tenantId = existing?.id ?? (await provisionWithRetry(token, code, adminEmail))

  if (options.billing) {
    await setTenantApp(token, tenantId, 'billing', true)
  }
  if (options.platform) {
    await setTenantApp(token, tenantId, 'platform', true)
  }

  // The host is a domain of the tenant, which is what makes Keycloak accept a sign-in from it: the domain add
  // registers the console redirect URI before the browser ever goes there.
  await addDomain(token, tenantId, env.tenantHost(code))
  await waitForRedirectUri(env.tenantHost(code))

  const username = keycloakUsername(code, adminEmail)
  await makeLoginable(username, TENANT_ADMIN_PASSWORD)
  const tenantToken = await directGrant(username, TENANT_ADMIN_PASSWORD)

  return {
    code,
    host: env.tenantHost(code),
    tenantId,
    adminEmail,
    adminPassword: TENANT_ADMIN_PASSWORD,
    token: tenantToken,
  }
}

/**
 * Creating a tenant also creates its Keycloak administrator, and a response that breaks mid-write leaves a tenant the
 * next attempt finds by its code. A transient server error is retried; anything else is the caller's to see.
 */
async function provisionWithRetry(token: string, code: string, adminEmail: string): Promise<string> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      return await provisionTenant(token, {
        code,
        name: `E2E ${code}`,
        adminEmail,
        adminTemporaryPassword: TENANT_ADMIN_PASSWORD,
      })
    } catch (failure) {
      const message = String(failure)
      const transient = /failed with 5\d\d/.test(message)
      if (!transient || attempt === 3) throw failure
      await new Promise((resolve) => setTimeout(resolve, 2_000))
      const created = await findTenant(token, code)
      if (created) return created.id
    }
  }
  throw new Error(`tenant ${code} could not be created`)
}

/** Maps the tenant's own hostname to it; the Platform registers the console redirect URI as part of it. */
async function addDomain(token: string, tenantId: string, hostname: string): Promise<void> {
  const answer = await call(`/platform/api/v1/tenants/${tenantId}/domains`, {
    method: 'POST',
    token,
    body: { hostname },
  })
  if (answer.status === 409) return
  requireOk(answer, `mapping ${hostname} to tenant ${tenantId}`)
}

async function waitForRedirectUri(hostname: string, timeoutMs = 30_000): Promise<void> {
  const expected = `http://${hostname}:${env.port}/console/*`
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if ((await consoleRedirectUris(env.consoleClientId)).includes(expected)) return
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`Keycloak never accepted the console redirect ${expected}`)
}

/** The highest registered version, which is what a fresh publish wants made live. */
function newestVersion(remote: RemoteResponse): string | undefined {
  return [...(remote.versions ?? [])].sort((left, right) => right.registeredAt.localeCompare(left.registeredAt)).at(0)
    ?.version
}

/**
 * The baseline every browser test runs on: the remote is enabled and points at its newest published version. It is
 * the operator's click through the API, because a test that cares about the click makes it through the UI instead.
 */
export async function ensureRemoteActive(name: string): Promise<RemoteResponse> {
  const token = await platformAdminToken()
  const remote = await findRemote(token, name)
  const target = newestVersion(remote)
  if (!target) throw new Error(`remote ${name} has no registered version; publish one before running the gate`)
  if (remote.enabled && remote.activeVersion === target) return remote
  return setRemoteState(token, remote, { enabled: true, activeVersion: target })
}

/**
 * Waits until Billing's replica of the tenant answers for this account. A new tenant's events can arrive out of order,
 * so the replica's own retry window (about a minute) is the budget this accepts.
 */
export async function waitForBilling(host: string, token: string, timeoutMs = 120_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let last = 0
  while (Date.now() < deadline) {
    const answer = await call<unknown>('/api/v1/customers', { token, host })
    last = answer.status
    if (answer.status === 200) return
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`Billing did not answer 200 for ${host} within ${timeoutMs}ms (last ${last})`)
}

export interface TenantSystem {
  token: string
  systemId: string
}

const BILLING_SCOPES = [
  'billing:customer:read',
  'billing:customer:write',
  'billing:charge:create',
  'billing:invoice:create',
  'billing:invoice:read',
  'billing:wallet:manage',
  'billing:wallet:read',
  'billing:usage:record',
  'billing:usage:read',
  'billing:webhook:manage',
  'billing:webhook:read',
  'billing:coupon:read',
  'billing:coupon:manage',
  'billing:coupon:redeem',
]

/** A Tenant System credential for the tenant-admin account, so a fixture can seed Billing without the browser. */
export async function tenantSystem(fixture: TenantFixture): Promise<TenantSystem> {
  const created = await call<{ id: string }>('/platform/api/v1/tenant-systems', {
    method: 'POST',
    token: fixture.token,
    host: fixture.host,
    body: { name: 'e2e' },
  })
  const systemId = requireOk(created, 'creating the tenant system').id

  const credential = await call<{ key: string; credential: { keyId: string } }>(
    `/platform/api/v1/tenant-systems/${systemId}/credentials`,
    {
      method: 'POST',
      token: fixture.token,
      host: fixture.host,
      body: { label: 'e2e', scopes: BILLING_SCOPES },
    },
  )
  const issued = requireOk(credential, 'issuing the tenant system credential')
  const keyId = issued.credential.keyId
  const secret = issued.key.slice(issued.key.lastIndexOf('_') + 1)

  const tokenResponse = await fetch(`${env.originFor(fixture.host)}/platform/oauth/token`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(`${keyId}:${secret}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({ grant_type: 'client_credentials', audience: 'billing' }),
  })
  if (!tokenResponse.ok) {
    throw new Error(`the tenant system could not get a token: ${tokenResponse.status} ${await tokenResponse.text()}`)
  }
  const token = (await tokenResponse.json()) as { access_token?: string }
  if (!token.access_token) throw new Error('the tenant system token response has no access_token')
  return { token: token.access_token, systemId }
}

async function until200(path: string, token: string, host: string, what: string, timeoutMs = 30_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let last = 0
  while (Date.now() < deadline) {
    const answer = await call<unknown>(path, { token, host })
    last = answer.status
    if (answer.status === 200) return
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`${what} still answered ${last} after ${timeoutMs}ms`)
}

export interface SeededBilling {
  customerId: string
  walletId: string
  chargeId: string
  invoiceId: string
  invoiceNumber: string
}

/** Money records a browser test needs to exercise a list, a sheet and the keyboard without building them by hand. */
export async function seedBilling(fixture: TenantFixture): Promise<SeededBilling> {
  const { token, systemId } = await tenantSystem(fixture)
  await until200('/api/v1/customers', token, fixture.host, 'the tenant system replica')

  const idempotency = (value: string) => ({ 'Idempotency-Key': `${value}-${fixture.code}` })
  const customer = requireOk(
    await call<{ id: string }>('/api/v1/customers', {
      method: 'POST',
      token,
      host: fixture.host,
      body: {
        tenantSystemId: systemId,
        externalUserId: `e2e-${fixture.code}`,
        displayName: 'E2E Customer',
        email: `e2e@${fixture.code}.test`,
      },
      headers: idempotency('customer'),
    }),
    'creating the seeded customer',
  )
  const wallet = requireOk(
    await call<{ id: string }>('/api/v1/wallets', {
      method: 'POST',
      token,
      host: fixture.host,
      body: { customerId: customer.id, currency: 'USD', mode: 'POSTPAID', creditLimit: 1_000_000 },
    }),
    'opening the seeded wallet',
  )
  const charge = requireOk(
    await call<{ id: string }>('/api/v1/charges', {
      method: 'POST',
      token,
      host: fixture.host,
      body: { walletId: wallet.id, amount: 1_200, currency: 'USD', reference: `e2e-${fixture.code}` },
      headers: idempotency('charge'),
    }),
    'creating the seeded charge',
  )
  const issued = await call<{ invoice: { id: string; number?: string } }>('/api/v1/invoices', {
    method: 'POST',
    token,
    host: fixture.host,
    body: { chargeId: charge.id },
  })
  // A charge is billed once. If the write reached Billing but its answer did not come back, the invoice exists and
  // the next request is refused; the customer's invoice list then names the one to use.
  let invoice: { id: string; number?: string }
  if (issued.status === 409) {
    const listed = requireOk(
      await call<{ data: Array<{ id: string; number?: string }> }>(
        `/api/v1/invoices?${new URLSearchParams({ 'filter[customer_id][eq]': customer.id, limit: '1' })}`,
        { token, host: fixture.host },
      ),
      'reading the invoice that already bills the seeded charge',
    )
    const existing = listed.data[0]
    if (!existing) throw new Error('a charge reported as billed has no invoice')
    invoice = existing
  } else {
    invoice = requireOk(issued, 'issuing the seeded invoice').invoice
  }
  // The creation answer may not carry the number the list shows, so the fixture reads the invoice back.
  const detail =
    invoice.number === undefined
      ? requireOk(
          await call<{ number?: string }>(`/api/v1/invoices/${invoice.id}`, { token, host: fixture.host }),
          'reading the seeded invoice',
        )
      : invoice

  await call('/api/v1/usage-events', {
    method: 'POST',
    token,
    host: fixture.host,
    body: { customerId: customer.id, metric: 'api_calls', quantity: '120', unit: 'call' },
  })
  await call('/api/v1/webhook-endpoints', {
    method: 'POST',
    token,
    host: fixture.host,
    body: {
      url: `https://${fixture.code}.example.test/hooks`,
      description: 'E2E endpoint',
      eventTypes: ['invoice.created'],
    },
  })

  return {
    customerId: customer.id,
    walletId: wallet.id,
    chargeId: charge.id,
    invoiceId: invoice.id,
    invoiceNumber: detail.number ?? invoice.number ?? '',
  }
}

/** What the shell's boot sees: the caller and the remotes the backend allows. */
export async function consoleAccess(host: string, token: string): Promise<{ me: MeResponse; remotes: string[] }> {
  const answer = await me(token, host)
  const feed = await call<Array<{ name: string }>>('/platform/api/v1/console/remotes', { token, host })
  return { me: answer, remotes: requireOk(feed, 'reading the console feed').map((entry) => entry.name) }
}
