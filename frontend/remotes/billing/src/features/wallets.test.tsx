import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allBillingPermissions, ids, pick, renderPage } from '../test-utils'
import { ChargesPage } from './charges/page'
import { ReservationsPage } from './reservations/page'
import { UsagePage } from './usage/page'
import { WalletsPage } from './wallets/page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const wallet = {
  id: 'w1',
  customerId: 'c1',
  currency: 'USD',
  mode: 'PREPAID',
  balance: { amount: 1000, currency: 'USD' },
  held: { amount: 0, currency: 'USD' },
  available: { amount: 1000, currency: 'USD' },
  createdAt: '2026-11-17T09:00:00Z',
}

const ledgerEntry = {
  id: 'le1',
  sequence: 1,
  type: 'CHARGE',
  amount: { amount: 500, currency: 'USD' },
  reference: 'ref-1',
  createdAt: '2026-11-17T09:00:00Z',
}

const reservation = {
  id: 'res1',
  walletId: 'w1',
  status: 'HELD',
  amount: { amount: 800, currency: 'USD' },
  captured: { amount: 0, currency: 'USD' },
  expiresAt: '2026-11-17T10:00:00Z',
  createdAt: '2026-11-17T09:00:00Z',
}

const charge = {
  id: 'ch1',
  status: 'SUCCEEDED',
  amount: { amount: 400, currency: 'USD' },
  grossAmount: { amount: 400, currency: 'USD' },
  reference: 'order-1',
}

describe('wallets', () => {
  it('shows the ledger and reverses an entry', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/wallets', () =>
        HttpResponse.json({ data: [wallet], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/wallets/w1/ledger', () =>
        HttpResponse.json({ data: [ledgerEntry], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/ledger-entries/le1/reverse', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ reversal: ledgerEntry, replayed: false })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <WalletsPage state={state} />, { permissions: allBillingPermissions })
    await actor.click((await screen.findAllByText('10.00 USD'))[0] as HTMLElement)
    expect(await screen.findByText('CHARGE')).toBeInTheDocument()
    await actor.click(await screen.findByRole('button', { name: 'Reverse entry' }))
    const dialog = await screen.findByRole('alertdialog')
    // A reversal needs a reason before it can be confirmed.
    expect(within(dialog).getByRole('button', { name: 'Reverse entry' })).toBeDisabled()
    await actor.type(within(dialog).getByLabelText('Reason'), 'duplicate')
    await actor.click(within(dialog).getByRole('button', { name: 'Reverse entry' }))
    await waitFor(() => expect(body).toEqual({ reason: 'duplicate' }))
  })

  it('hides opening a wallet without the permission', async () => {
    server.use(
      http.get('http://console.test/api/v1/wallets', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <WalletsPage state={state} />, { permissions: ['billing:wallet:read'] })
    expect(await screen.findByRole('heading', { name: 'Wallets' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open wallet' })).not.toBeInTheDocument()
  })
})

describe('reservations', () => {
  it('captures a held reservation', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/reservations', () =>
        HttpResponse.json({ data: [reservation], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/reservations/res1/capture', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ ...reservation, status: 'CAPTURED' })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <ReservationsPage state={state} />, { permissions: allBillingPermissions })
    await actor.click(await screen.findByText('8.00 USD'))
    await actor.click(await screen.findByRole('button', { name: 'Capture' }))
    const dialogs = screen.getAllByRole('dialog')
    await actor.click(within(dialogs[dialogs.length - 1] as HTMLElement).getByRole('button', { name: 'Capture' }))
    await waitFor(() => expect(body).toMatchObject({ amount: 800, currency: 'USD' }))
  })
})

describe('charges', () => {
  it('creates a charge', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/charges', () =>
        HttpResponse.json({ data: [charge], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/wallets', () =>
        HttpResponse.json({ data: [{ ...wallet, id: ids.wallet, customerId: ids.customer }] }),
      ),
      http.get(`http://console.test/api/v1/wallets/${ids.wallet}`, () =>
        HttpResponse.json({ ...wallet, id: ids.wallet }),
      ),
      http.get('http://console.test/api/v1/customers', () =>
        HttpResponse.json({ data: [{ id: ids.customer, displayName: 'Ana' }] }),
      ),
      http.post('http://console.test/api/v1/charges', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(charge, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <ChargesPage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('4.00 USD')).toBeInTheDocument()
    await actor.click((await screen.findAllByRole('button', { name: 'Create charge' }))[0] as HTMLElement)
    await pick(actor, 'Wallet', /Ana · USD/)
    await actor.type(screen.getByLabelText('Amount'), '4')
    const dialogs = screen.getAllByRole('dialog')
    await actor.click(within(dialogs[dialogs.length - 1] as HTMLElement).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(body).toMatchObject({ walletId: ids.wallet, amount: 400, currency: 'USD' }))
  })
})

describe('usage', () => {
  it('lists events and records one', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/usage-events', () =>
        HttpResponse.json({
          data: [
            {
              id: 'ev1',
              customerId: 'c1',
              metric: 'seats',
              quantity: '5',
              unit: 'seat',
              occurredAt: '2026-11-17T09:00:00Z',
            },
          ],
          page: { limit: 50, offset: 0, total: 1 },
        }),
      ),
      http.get('http://console.test/api/v1/customers', () =>
        HttpResponse.json({ data: [{ id: ids.customer, displayName: 'Ana' }] }),
      ),
      http.post('http://console.test/api/v1/usage-events', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ event: { id: 'ev2', metric: 'api_calls' }, replayed: false }, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <UsagePage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('seats')).toBeInTheDocument()
    await actor.click((await screen.findAllByRole('button', { name: 'Record usage' }))[0] as HTMLElement)
    await pick(actor, 'Customer', /Ana/)
    await actor.type(screen.getByLabelText('Metric'), 'api_calls')
    await actor.type(screen.getByLabelText('Quantity'), '1')
    const dialogs = screen.getAllByRole('dialog')
    await actor.click(within(dialogs[dialogs.length - 1] as HTMLElement).getByRole('button', { name: 'Record usage' }))
    await waitFor(() => expect(body).toMatchObject({ customerId: ids.customer, metric: 'api_calls', quantity: '1' }))
  })

  it('shows aggregates from the server', async () => {
    server.use(
      http.get('http://console.test/api/v1/usage-events', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
      http.get('http://console.test/api/v1/customers', () =>
        HttpResponse.json({ data: [{ id: ids.customer, displayName: 'Ana' }] }),
      ),
      http.get('http://console.test/api/v1/usage-aggregates', () =>
        HttpResponse.json([{ metric: 'seats', unit: 'seat', quantity: '12', events: 3 }]),
      ),
    )
    const actor = userEvent.setup()
    renderPage((state) => <UsagePage state={state} />, { permissions: allBillingPermissions })
    await actor.click(await screen.findByRole('tab', { name: 'Aggregates' }))
    // The period starts as the last 30 days; only the customer is needed.
    await pick(actor, 'Customer', /Ana/)
    await actor.click(screen.getByRole('button', { name: 'Show totals' }))
    expect((await screen.findAllByText('12')).length).toBeGreaterThan(0)
  })
})
