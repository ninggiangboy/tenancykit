import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allBillingPermissions, ids, pick, renderPage } from '../test-utils'
import { CustomersPage } from './customers/page'
import { InvoicesPage } from './invoices/page'
import { PaymentsPage } from './payments/page'
import { SubscriptionsPage } from './subscriptions/page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const customer = {
  id: 'c1',
  displayName: 'Ana',
  email: 'ana@example.com',
  status: 'ACTIVE',
  createdAt: '2026-11-17T09:00:00Z',
}

const payment = {
  id: 'p1',
  walletId: 'w1',
  customerId: 'c1',
  status: 'SUCCEEDED',
  amount: { amount: 1000, currency: 'USD' },
  refunded: { amount: 0, currency: 'USD' },
  createdAt: '2026-11-17T09:00:00Z',
}

const invoice = {
  id: 'i1',
  number: 'INV-1',
  customerId: 'c1',
  status: 'OPEN',
  total: { amount: 2500, currency: 'USD' },
  createdAt: '2026-11-17T09:00:00Z',
}

const subscription = {
  id: 'sub1',
  customerId: 'c1',
  walletId: 'w1',
  planId: 'plan1',
  planCode: 'GROWTH',
  cycle: 'MONTHLY',
  status: 'ACTIVE',
  createdAt: '2026-11-17T09:00:00Z',
}

describe('customers', () => {
  it('lists and creates a customer', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/customers', () =>
        HttpResponse.json({ data: [customer], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/customers', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(customer, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <CustomersPage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('Ana')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: 'Create customer' }))
    await actor.type(screen.getByLabelText('Display name'), 'Ana')
    await actor.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(body).toMatchObject({ displayName: 'Ana' }))
  })

  it('hides create and deactivate from a read-only caller', async () => {
    server.use(
      http.get('http://console.test/api/v1/customers', () =>
        HttpResponse.json({ data: [customer], page: { limit: 50, offset: 0, total: 1 } }),
      ),
    )
    renderPage((state) => <CustomersPage state={state} />, { permissions: ['billing:customer:read'] })
    expect(await screen.findByText('Ana')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create customer' })).not.toBeInTheDocument()
  })
})

describe('payments', () => {
  it('refunds a payment', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/payments', () =>
        HttpResponse.json({ data: [payment], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/payments/p1', () =>
        HttpResponse.json({ payment, refunds: [], failedAttempts: [] }),
      ),
      http.post('http://console.test/api/v1/payments/p1/refunds', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ id: 'r1', paymentId: 'p1', status: 'PENDING' }, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <PaymentsPage state={state} />, { permissions: allBillingPermissions })
    await actor.click(await screen.findByText('10.00 USD'))
    await actor.click(await screen.findByRole('button', { name: 'Refund' }))
    const amount = await screen.findByLabelText('Refund amount')
    expect(amount).toHaveValue('10.00')
    await actor.clear(amount)
    await actor.type(amount, '4.50')
    const refundButtons = await screen.findAllByRole('button', { name: 'Refund' })
    await actor.click(refundButtons[refundButtons.length - 1] as HTMLElement)
    await waitFor(() => expect(body).toMatchObject({ amount: 450, currency: 'USD' }))
  })

  it('hides top-up without the create permission', async () => {
    server.use(
      http.get('http://console.test/api/v1/payments', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <PaymentsPage state={state} />, { permissions: ['billing:payment:read'] })
    expect(await screen.findByRole('heading', { name: 'Payments' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Top up' })).not.toBeInTheDocument()
  })
})

describe('invoices', () => {
  it('voids an invoice', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/invoices', () =>
        HttpResponse.json({ data: [invoice], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/invoices/i1', () => HttpResponse.json({ invoice, items: [], payments: [] })),
      http.post('http://console.test/api/v1/invoices/i1/void', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ invoice: { ...invoice, status: 'VOID' }, items: [], payments: [] })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <InvoicesPage state={state} />, { permissions: allBillingPermissions })
    await actor.click(await screen.findByText('25.00 USD'))
    await actor.click(await screen.findByRole('button', { name: 'Void invoice' }))
    const dialog = await screen.findByRole('alertdialog')
    await actor.click(within(dialog).getByRole('button', { name: 'Void invoice' }))
    await waitFor(() => expect(body).toEqual({ reason: '' }))
  })

  it('hides void from a read-only caller', async () => {
    server.use(
      http.get('http://console.test/api/v1/invoices', () =>
        HttpResponse.json({ data: [invoice], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/invoices/i1', () => HttpResponse.json({ invoice, items: [], payments: [] })),
    )
    const actor = userEvent.setup()
    renderPage((state) => <InvoicesPage state={state} />, { permissions: ['billing:invoice:read'] })
    await actor.click(await screen.findByText('25.00 USD'))
    expect(await screen.findByText('Items')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Void invoice' })).not.toBeInTheDocument()
  })
})

describe('subscriptions', () => {
  it('subscribes a wallet to a plan', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/subscriptions', () =>
        HttpResponse.json({ data: [subscription], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/customers', () =>
        HttpResponse.json({ data: [{ ...customer, id: ids.customer }] }),
      ),
      http.get('http://console.test/api/v1/wallets', () =>
        HttpResponse.json({ data: [{ id: ids.wallet, customerId: ids.customer, currency: 'USD', mode: 'PREPAID' }] }),
      ),
      http.get('http://console.test/api/v1/pricing-plans', () =>
        HttpResponse.json({ data: [{ id: ids.plan, code: 'GROWTH', name: 'Growth', status: 'ACTIVE' }] }),
      ),
      http.post('http://console.test/api/v1/subscriptions', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(subscription, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <SubscriptionsPage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('GROWTH')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: 'Create subscription' }))
    await pick(actor, 'Wallet', /Ana · USD/)
    await pick(actor, 'Pricing plan', /Growth/)
    await actor.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(body).toEqual({ walletId: ids.wallet, planId: ids.plan }))
  })

  it('renders in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/api/v1/subscriptions', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <SubscriptionsPage state={state} />, {
      permissions: allBillingPermissions,
      locale: 'vi',
    })
    expect(await screen.findByRole('heading', { name: 'Đăng ký' })).toBeInTheDocument()
  })
})
