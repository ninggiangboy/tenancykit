import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allBillingPermissions, renderPage } from '../test-utils'
import { CouponsPage } from './coupons/page'
import { PlansPage } from './plans/page'
import { PricingPage } from './pricing/page'
import { RedemptionsPage } from './redemptions/page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const plan = {
  id: 'plan1',
  code: 'GROWTH',
  name: 'Growth',
  model: 'FLAT',
  currency: 'USD',
  status: 'ACTIVE',
  createdAt: '2026-11-17T09:00:00Z',
  versions: [{ version: 1, billingCycle: 'MONTHLY', rule: { model: 'FLAT', metric: 'seats', unitPrice: '500' } }],
}

const coupon = {
  id: 'cp1',
  name: 'Launch',
  benefitType: 'PERCENT',
  percentBasisPoints: 1000,
  currency: 'USD',
  status: 'ACTIVE',
  createdAt: '2026-11-17T09:00:00Z',
}

const redemption = {
  id: 'red1',
  couponId: 'cp1',
  couponName: 'Launch',
  walletId: 'w1',
  customerId: 'c1',
  status: 'ACTIVE',
  applicationsUsed: 1,
  maxApplications: 3,
  discountTotal: 250,
  currency: 'USD',
  redeemedAt: '2026-11-17T09:00:00Z',
}

function lastDialog(): HTMLElement {
  const dialogs = screen.getAllByRole('dialog')
  return dialogs[dialogs.length - 1] as HTMLElement
}

describe('pricing plans', () => {
  it('creates a plan with its first version', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/pricing-plans', () =>
        HttpResponse.json({ data: [plan], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/pricing-plans', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(plan, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <PlansPage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('Growth')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: 'Create plan' }))
    await actor.type(screen.getByLabelText('Plan code'), 'STARTER')
    await actor.type(screen.getByLabelText('Name'), 'Starter')
    await actor.click(within(lastDialog()).getByRole('button', { name: 'Create' }))
    await waitFor(() =>
      expect(body).toMatchObject({ code: 'STARTER', name: 'Starter', version: { billingCycle: 'MONTHLY' } }),
    )
  })

  it('publishes a new immutable version', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/pricing-plans', () =>
        HttpResponse.json({ data: [plan], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/pricing-plans/plan1/versions', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(plan, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <PlansPage state={state} />, { permissions: allBillingPermissions })
    await actor.click(await screen.findByText('Growth'))
    await actor.click(await screen.findByRole('button', { name: 'Publish version' }))
    const unitPrice = await screen.findByLabelText('Unit price')
    await actor.clear(unitPrice)
    await actor.type(unitPrice, '750')
    const dialog = unitPrice.closest('[role="dialog"]') as HTMLElement
    await actor.click(within(dialog).getByRole('button', { name: 'Publish version' }))
    await waitFor(() => expect(body).toMatchObject({ rule: { unitPrice: '750' } }))
  })
})

describe('pricing check', () => {
  it('renders the server estimate', async () => {
    server.use(
      http.post('http://console.test/api/v1/pricing/estimates', () =>
        HttpResponse.json({
          planId: 'plan1',
          planVersion: 1,
          amount: { amount: 2500, currency: 'USD' },
          grossAmount: { amount: 2500, currency: 'USD' },
          discounts: [],
          lines: [{ description: 'Seats', metric: 'seats', quantity: '5', amount: '25.00' }],
        }),
      ),
    )
    const actor = userEvent.setup()
    renderPage((state) => <PricingPage state={state} />, { permissions: allBillingPermissions })
    await actor.type(screen.getByLabelText('Plan ID'), 'plan1')
    await actor.click(screen.getByRole('button', { name: 'Estimate' }))
    expect((await screen.findAllByText('25.00 USD')).length).toBeGreaterThan(0)
  })
})

describe('coupons', () => {
  it('creates a coupon and adds a code', async () => {
    let couponBody: unknown
    let codeBody: unknown
    server.use(
      http.get('http://console.test/api/v1/coupons', () =>
        HttpResponse.json({ data: [coupon], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/coupons', async ({ request }) => {
        couponBody = await request.json()
        return HttpResponse.json(coupon, { status: 201 })
      }),
      http.get('http://console.test/api/v1/coupons/cp1/codes', () =>
        HttpResponse.json({
          data: [{ id: 'code1', code: 'LAUNCH', couponId: 'cp1', active: true, redeemedCount: 0 }],
          page: { limit: 50, offset: 0, total: 1 },
        }),
      ),
      http.post('http://console.test/api/v1/coupons/cp1/codes', async ({ request }) => {
        codeBody = await request.json()
        return HttpResponse.json({ id: 'code2', code: 'LAUNCH2', couponId: 'cp1', active: true }, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <CouponsPage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('Launch')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: 'Create coupon' }))
    await actor.type(screen.getByLabelText('Name'), 'Launch')
    await actor.click(within(lastDialog()).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(couponBody).toMatchObject({ name: 'Launch' }))

    await actor.click(screen.getByText('Launch'))
    expect(await screen.findByText('LAUNCH')).toBeInTheDocument()
    await actor.type(screen.getByLabelText('Code'), 'LAUNCH2')
    await actor.click(screen.getByRole('button', { name: 'Add code' }))
    await waitFor(() => expect(codeBody).toMatchObject({ code: 'LAUNCH2' }))
  })
})

describe('redemptions', () => {
  it('lists a wallet redemption and revokes it', async () => {
    let revoked: string | null = null
    server.use(
      http.get('http://console.test/api/v1/wallets/w1/coupon-redemptions', () =>
        HttpResponse.json({ data: [redemption], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/coupon-redemptions/red1/revoke', () => {
        revoked = 'red1'
        return HttpResponse.json({ ...redemption, status: 'REVOKED' })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <RedemptionsPage state={state} />, { permissions: allBillingPermissions })
    await actor.type(screen.getByLabelText('Wallet ID'), 'w1')
    await actor.click(screen.getByRole('button', { name: 'Search' }))
    await actor.click(await screen.findByText('Launch'))
    await actor.click(await screen.findByRole('button', { name: 'Revoke redemption' }))
    const dialog = await screen.findByRole('alertdialog')
    await actor.click(within(dialog).getByRole('button', { name: 'Revoke redemption' }))
    await waitFor(() => expect(revoked).toBe('red1'))
  })
})
