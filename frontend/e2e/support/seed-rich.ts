import { call } from './platform'
import { type TenantFixture, tenantSystem, until200 } from './stack'

/**
 * A tenant with enough in every Billing list to look at it: seven customers (one deactivated) with a wallet each in
 * four currencies, prepaid and postpaid; charges and invoices that are open, void and uncollectible; six pricing plans
 * of every model, one with a second version and one retired; subscriptions, one canceled; coupons of each benefit with
 * codes, redemptions and a deactivated coupon; reservations held, captured and released; usage events over two weeks
 * in three metrics; and four webhook endpoints.
 *
 * Payments and refunds are not here: Billing creates them only from Stripe's answers, and the stack runs without a
 * Stripe account, so those two screens stay empty. The steps that are not essential report what they could not do in
 * the returned notes instead of failing the run, because the point is to have something to look at.
 */
export interface RichBilling {
  customers: number
  wallets: number
  invoices: number
  plans: number
  subscriptions: number
  coupons: number
  redemptions: number
  reservations: number
  usageEvents: number
  webhookEndpoints: number
  /** What a non-essential step could not do, with the server's answer. */
  notes: string[]
}

interface Person {
  name: string
  currency: 'USD' | 'VND' | 'EUR' | 'GBP'
  mode: 'POSTPAID' | 'PREPAID'
  creditLimit: number
  /** Charge amounts in minor units; a prepaid wallet holds no balance, so it gets none. */
  charges: number[]
}

const PEOPLE: Person[] = [
  { name: 'Lumen Coffee', currency: 'USD', mode: 'POSTPAID', creditLimit: 5_000_000, charges: [12_500, 8_640, 32_000] },
  {
    name: 'Northwind Labs',
    currency: 'USD',
    mode: 'POSTPAID',
    creditLimit: 5_000_000,
    charges: [4_120, 150_000, 9_900],
  },
  {
    name: 'Bánh Mì 37',
    currency: 'VND',
    mode: 'POSTPAID',
    creditLimit: 500_000_000,
    charges: [3_200_000, 18_500_000, 450_000],
  },
  { name: 'Orbit Dental', currency: 'USD', mode: 'PREPAID', creditLimit: 0, charges: [] },
  { name: 'Kestrel Freight', currency: 'EUR', mode: 'POSTPAID', creditLimit: 2_000_000, charges: [5_325, 129_900] },
  { name: 'Fieldnote Studio', currency: 'GBP', mode: 'PREPAID', creditLimit: 0, charges: [] },
  { name: 'Harbor Books', currency: 'USD', mode: 'POSTPAID', creditLimit: 100_000, charges: [1_299] },
]

type Answer<T> = Awaited<ReturnType<typeof call<T>>>

export async function seedBillingRich(fixture: TenantFixture): Promise<RichBilling> {
  const { token, systemId } = await tenantSystem(fixture, { name: 'e2e-rich' })
  await until200('/api/v1/customers', token, fixture.host, 'the tenant system replica')

  const notes: string[] = []
  const send = async <T>(
    path: string,
    body?: unknown,
    options: { method?: string; idempotency?: string } = {},
  ): Promise<Answer<T>> => {
    const request = (bearer: string) =>
      call<T>(path, {
        method: options.method ?? 'POST',
        token: bearer,
        host: fixture.host,
        body,
        headers: options.idempotency ? { 'Idempotency-Key': `${options.idempotency}-${fixture.code}` } : {},
      })
    // A tenant system is not granted what only an operator may do (plans, subscriptions, voiding); the tenant's
    // administrator does those through the console, so a refusal here is retried as the administrator.
    const first = await request(token)
    return first.status === 401 || first.status === 403 ? request(fixture.token) : first
  }
  /** A step the screens do not depend on: its failure is a note, not an error. */
  const attempt = async <T>(label: string, answer: Promise<Answer<T>>): Promise<T | null> => {
    const result = await answer
    // An answer with no body (204) is still a success, and the callers read it as one.
    if (result.status >= 200 && result.status < 300) return result.body ?? ({} as T)
    notes.push(`${label}: ${result.status} ${JSON.stringify(result.body).slice(0, 200)}`)
    return null
  }
  /** A step later steps need: its failure stops the seed. */
  const need = async <T>(label: string, answer: Promise<Answer<T>>): Promise<T> => {
    const result = await answer
    if (result.status < 200 || result.status >= 300) {
      throw new Error(`${label} failed with ${result.status}: ${JSON.stringify(result.body)}`)
    }
    return result.body
  }

  // Customers, each with a wallet.
  const wallets: Array<{ person: Person; customerId: string; walletId: string }> = []
  for (const [index, person] of PEOPLE.entries()) {
    const customer = await need<{ id: string }>(
      `customer ${person.name}`,
      send(
        '/api/v1/customers',
        {
          tenantSystemId: systemId,
          externalUserId: `rich-${fixture.code}-${index}`,
          displayName: person.name,
          email: `${person.name.toLowerCase().replace(/[^a-z0-9]+/g, '.')}@${fixture.code}.test`,
        },
        { idempotency: `rich-customer-${index}` },
      ),
    )
    const wallet = await need<{ id: string }>(
      `wallet of ${person.name}`,
      send('/api/v1/wallets', {
        customerId: customer.id,
        currency: person.currency,
        mode: person.mode,
        creditLimit: person.creditLimit,
      }),
    )
    wallets.push({ person, customerId: customer.id, walletId: wallet.id })
  }

  // Charges, and an invoice for each: the first stays open, the second is voided, the third is written off.
  let invoices = 0
  for (const [index, entry] of wallets.entries()) {
    for (const [n, amount] of entry.person.charges.entries()) {
      const charge = await attempt<{ id: string }>(
        `charge ${index}/${n}`,
        send(
          '/api/v1/charges',
          {
            walletId: entry.walletId,
            amount,
            currency: entry.person.currency,
            reference: `rich-${fixture.code}-${index}-${n}`,
          },
          { idempotency: `rich-charge-${index}-${n}` },
        ),
      )
      if (!charge) continue
      const invoice = await attempt<{ invoice?: { id: string } }>(
        `invoice ${index}/${n}`,
        send('/api/v1/invoices', { chargeId: charge.id }),
      )
      const invoiceId = invoice?.invoice?.id
      if (!invoiceId) continue
      invoices++
      if (n === 1)
        await attempt(`void ${index}/${n}`, send(`/api/v1/invoices/${invoiceId}/void`, { reason: 'Billed twice' }))
      if (n === 2) {
        await attempt(
          `uncollectible ${index}/${n}`,
          send(`/api/v1/invoices/${invoiceId}/uncollectible`, { reason: 'Customer unreachable' }),
        )
      }
    }
  }

  // The customer who left.
  const former = wallets.at(-1)
  if (former) await attempt('deactivate customer', send(`/api/v1/customers/${former.customerId}/deactivate`))

  // Pricing plans of every model.
  const usage = { metric: 'api_calls', unit: 'call', unitPrice: '0.002' }
  const planDefinitions = [
    {
      code: 'starter',
      name: 'Starter',
      currency: 'USD',
      model: 'SUBSCRIPTION',
      cycle: 'MONTHLY',
      rule: { fixedPrice: '29.00' },
    },
    {
      code: 'growth',
      name: 'Growth',
      currency: 'USD',
      model: 'SUBSCRIPTION',
      cycle: 'YEARLY',
      rule: { fixedPrice: '990.00' },
    },
    { code: 'api-calls', name: 'API calls', currency: 'USD', model: 'USAGE_BASED', cycle: 'MONTHLY', rule: usage },
    {
      code: 'compute-time',
      name: 'Compute time',
      currency: 'USD',
      model: 'TIME_BASED',
      cycle: 'MONTHLY',
      rule: { metric: 'compute_seconds', unit: 'second', unitPrice: '0.0005', increment: '60' },
    },
    {
      code: 'platform-hybrid',
      name: 'Platform and usage',
      currency: 'USD',
      model: 'HYBRID',
      cycle: 'MONTHLY',
      rule: { fixedPrice: '99.00', components: [{ model: 'USAGE_BASED', ...usage }] },
    },
    {
      code: 'storage',
      name: 'Storage',
      currency: 'USD',
      model: 'USAGE_BASED',
      cycle: 'MONTHLY',
      rule: { metric: 'storage_gb', unit: 'gb', unitPrice: '0.10' },
    },
    {
      code: 'batch-jobs',
      name: 'Batch jobs',
      currency: 'VND',
      model: 'WORKLOAD_BASED',
      cycle: 'MONTHLY',
      rule: { metric: 'jobs', unit: 'job', unitPrice: '1500', executionFee: '200' },
    },
  ]
  const plans: Record<string, string> = {}
  for (const plan of planDefinitions) {
    const created = await attempt<{ id: string }>(
      `plan ${plan.code}`,
      send('/api/v1/pricing-plans', {
        code: plan.code,
        name: plan.name,
        currency: plan.currency,
        model: plan.model,
        version: { billingCycle: plan.cycle, rule: { model: plan.model, ...plan.rule } },
      }),
    )
    if (created) plans[plan.code] = created.id
  }
  if (plans.starter) {
    await attempt(
      'starter version 2',
      send(`/api/v1/pricing-plans/${plans.starter}/versions`, {
        billingCycle: 'MONTHLY',
        rule: { model: 'SUBSCRIPTION', fixedPrice: '39.00' },
      }),
    )
  }
  if (plans['batch-jobs'])
    await attempt('retire batch-jobs', send(`/api/v1/pricing-plans/${plans['batch-jobs']}/retire`))

  // Subscriptions: two active, one canceled.
  let subscriptions = 0
  const subscribers: Array<[number, string]> = [
    [0, 'starter'],
    [1, 'growth'],
    [3, 'platform-hybrid'],
  ]
  for (const [index, code] of subscribers) {
    const wallet = wallets[index]
    const planId = plans[code]
    if (!wallet || !planId) continue
    const subscription = await attempt<{ subscription?: { id?: string } }>(
      `subscribe ${code}`,
      send('/api/v1/subscriptions', { walletId: wallet.walletId, planId }, { idempotency: `rich-sub-${code}` }),
    )
    if (!subscription) continue
    subscriptions++
    const subscriptionId = subscription.subscription?.id
    if (code === 'growth' && subscriptionId) {
      await attempt('cancel growth', send(`/api/v1/subscriptions/${subscriptionId}/cancel`))
    }
  }

  // Coupons: each benefit, with codes, one extra code, one deactivated.
  const couponDefinitions = [
    {
      name: 'Welcome 10%',
      code: 'WELCOME10',
      benefit: { type: 'PERCENT_OFF', basisPoints: 1000 },
      limits: { maxRedemptions: 100 },
    },
    {
      name: 'Spring $5 off',
      code: 'SPRING5',
      benefit: { type: 'AMOUNT_OFF', amount: 500 },
      limits: { maxRedemptions: 200, maxPerCustomer: 1 },
    },
    {
      name: 'Wallet credit $20',
      code: 'CREDIT20',
      benefit: { type: 'WALLET_CREDIT', amount: 2_000 },
      limits: { maxRedemptions: 500 },
    },
    {
      name: 'Black Friday',
      code: 'BF2026',
      benefit: { type: 'PERCENT_OFF', basisPoints: 3000 },
      limits: { maxRedemptions: 1_000 },
    },
  ]
  const coupons: Record<string, string> = {}
  for (const coupon of couponDefinitions) {
    const created = await attempt<{ coupon?: { id?: string } }>(
      `coupon ${coupon.code}`,
      send('/api/v1/coupons', {
        name: coupon.name,
        currency: 'USD',
        benefit: coupon.benefit,
        limits: coupon.limits,
        stackable: coupon.code === 'CREDIT20',
        code: coupon.code,
        codeMaxRedemptions: 50,
      }),
    )
    const couponId = created?.coupon?.id
    if (couponId) coupons[coupon.code] = couponId
  }
  if (coupons.WELCOME10) {
    await attempt(
      'extra code',
      send(`/api/v1/coupons/${coupons.WELCOME10}/codes`, { code: 'WELCOME10-VIP', maxRedemptions: 5 }),
    )
  }
  if (coupons.BF2026) {
    await attempt(
      'deactivate coupon',
      send(`/api/v1/coupons/${coupons.BF2026}/deactivate`, { revokeRedemptions: false }),
    )
  }

  // Redemptions: one in force, one revoked.
  let redemptions = 0
  const first = wallets[0]
  const second = wallets[1]
  if (first && coupons.WELCOME10) {
    const redeemed = await attempt(
      'redeem WELCOME10',
      send(
        `/api/v1/wallets/${first.walletId}/coupon-redemptions/attach`,
        { couponId: coupons.WELCOME10 },
        { idempotency: 'rich-redeem-1' },
      ),
    )
    if (redeemed) redemptions++
  }
  if (second && coupons.SPRING5) {
    const redeemed = await attempt<{ id: string }>(
      'redeem SPRING5',
      send(
        `/api/v1/wallets/${second.walletId}/coupon-redemptions/attach`,
        { couponId: coupons.SPRING5 },
        { idempotency: 'rich-redeem-2' },
      ),
    )
    if (redeemed) {
      redemptions++
      if (redeemed.id) await attempt('revoke redemption', send(`/api/v1/coupon-redemptions/${redeemed.id}/revoke`))
    }
  }

  // Reservations: one captured, one released, two still held.
  let reservations = 0
  if (first) {
    const hold = (amount: number, reference: string) =>
      attempt<{ id: string }>(
        `reserve ${reference}`,
        send(
          '/api/v1/reservations',
          { walletId: first.walletId, amount, currency: 'USD', ttlSeconds: 3_600, reference },
          { idempotency: `rich-${reference}` },
        ),
      )
    const toCapture = await hold(5_000, 'rich-capture')
    const toRelease = await hold(3_000, 'rich-release')
    const held = await hold(8_000, 'rich-held')
    reservations = [toCapture, toRelease, held].filter(Boolean).length
    if (toCapture) {
      await attempt(
        'capture',
        send(
          `/api/v1/reservations/${toCapture.id}/capture`,
          { amount: 4_500, currency: 'USD', reference: 'rich-capture' },
          { idempotency: 'rich-capture-now' },
        ),
      )
    }
    if (toRelease) await attempt('release', send(`/api/v1/reservations/${toRelease.id}/release`))
  }
  if (second) {
    const held = await attempt(
      'reserve second',
      send(
        '/api/v1/reservations',
        { walletId: second.walletId, amount: 2_000, currency: 'USD', ttlSeconds: 7_200, reference: 'rich-held-2' },
        { idempotency: 'rich-held-2' },
      ),
    )
    if (held) reservations++
  }

  // Usage over two weeks, three metrics, three customers.
  const metrics: Array<[string, string, number]> = [
    ['api_calls', 'call', 1_200],
    ['storage_gb', 'gb', 40],
    ['compute_seconds', 'second', 900],
  ]
  const events: Array<Record<string, string>> = []
  for (const entry of wallets.slice(0, 3)) {
    for (let day = 0; day < 8; day++) {
      const [metric, unit, base] = metrics[day % metrics.length] as [string, string, number]
      events.push({
        customerId: entry.customerId,
        metric,
        unit,
        quantity: String(base + day * 37),
        occurredAt: new Date(Date.now() - (day * 2 + 1) * 86_400_000).toISOString(),
        idempotencyKey: `rich-${fixture.code}-${entry.customerId}-${day}`,
      })
    }
  }
  const batch = await attempt<{ results?: unknown[] }>('usage batch', send('/api/v1/usage-events/batch', { events }))

  // Webhook endpoints.
  const endpoints = [
    ['finance', ['invoice.created', 'invoice.paid', 'payment.succeeded', 'payment.failed']],
    ['alerts', ['wallet.debited', 'reservation.expired', 'charge.failed']],
    ['audit', ['customer.created', 'pricing-plan.version-published', 'pricing-plan.retired']],
    ['marketing', ['coupon.redeemed', 'coupon.redemption-ended']],
  ] as const
  let webhookEndpoints = 0
  for (const [name, eventTypes] of endpoints) {
    const created = await attempt(
      `webhook ${name}`,
      send('/api/v1/webhook-endpoints', {
        url: `https://${fixture.code}.example.test/hooks/${name}`,
        description: `${name[0]?.toUpperCase()}${name.slice(1)} feed`,
        eventTypes,
      }),
    )
    if (created) webhookEndpoints++
  }

  return {
    customers: wallets.length,
    wallets: wallets.length,
    invoices,
    plans: Object.keys(plans).length,
    subscriptions,
    coupons: Object.keys(coupons).length,
    redemptions,
    reservations,
    usageEvents: batch?.results?.length ?? 0,
    webhookEndpoints,
    notes,
  }
}
