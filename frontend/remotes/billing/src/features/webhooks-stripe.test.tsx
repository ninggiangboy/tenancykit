import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allBillingPermissions, renderPage } from '../test-utils'
import { StripePage } from './stripe/page'
import { WebhooksPage } from './webhooks/page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const endpoint = {
  id: 'e1',
  url: 'https://example.com/hooks',
  description: 'Production',
  eventTypes: ['invoice.paid'],
  status: 'ACTIVE',
  createdAt: '2026-11-17T09:00:00Z',
}

const delivery = {
  id: 'd1',
  endpointId: 'e1',
  eventType: 'invoice.paid',
  status: 'DELIVERED',
  attempts: 1,
  lastStatusCode: 200,
  createdAt: '2026-11-17T09:00:00Z',
}

const configuration = {
  id: 'cfg1',
  mode: 'CONNECT',
  accountId: 'acct_1',
  status: 'ACTIVE',
  publishableKey: 'pk_test',
  webhookSecretSet: true,
  cardPaymentsActive: true,
  webhookUrl: 'https://example.com/stripe',
  updatedAt: '2026-11-17T09:00:00Z',
}

const event = {
  id: 'ev1',
  stripeEventId: 'evt_1',
  type: 'invoice.paid',
  status: 'DEAD',
  attempts: 1,
  receivedAt: '2026-11-17T09:00:00Z',
}

function lastDialog(): HTMLElement {
  const dialogs = screen.getAllByRole('dialog')
  return dialogs[dialogs.length - 1] as HTMLElement
}

describe('webhooks', () => {
  it('creates an endpoint and shows its signing secret once', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/api/v1/webhook-endpoints', () =>
        HttpResponse.json({ data: [endpoint], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/webhook-endpoints', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ endpoint, signingSecret: 'whsec_once' }, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <WebhooksPage state={state} />, { permissions: allBillingPermissions })
    expect(await screen.findByText('https://example.com/hooks')).toBeInTheDocument()
    await actor.click((await screen.findAllByRole('button', { name: 'Create endpoint' }))[0] as HTMLElement)
    await actor.type(screen.getByLabelText('Endpoint URL'), 'https://example.com/hooks')
    await actor.click(screen.getByLabelText('Event types'))
    await actor.click(await screen.findByRole('option', { name: 'invoice.paid' }))
    await actor.click(within(lastDialog()).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(body).toMatchObject({ url: 'https://example.com/hooks', eventTypes: ['invoice.paid'] }))
    expect(await screen.findByText('whsec_once')).toBeInTheDocument()
  })

  it('rotates the signing secret and lists deliveries', async () => {
    let rotated = false
    server.use(
      http.get('http://console.test/api/v1/webhook-endpoints', () =>
        HttpResponse.json({ data: [endpoint], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/webhook-deliveries', () =>
        HttpResponse.json({ data: [delivery], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.post('http://console.test/api/v1/webhook-endpoints/e1/rotate-secret', () => {
        rotated = true
        return HttpResponse.json({ endpoint, signingSecret: 'whsec_new' })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <WebhooksPage state={state} />, { permissions: allBillingPermissions })
    await actor.click(await screen.findByText('https://example.com/hooks'))
    expect(await screen.findByText('Delivered')).toBeInTheDocument()
    await actor.click(await screen.findByRole('button', { name: 'Rotate secret' }))
    const dialog = await screen.findByRole('alertdialog')
    await actor.click(within(dialog).getByRole('button', { name: 'Rotate secret' }))
    await waitFor(() => expect(rotated).toBe(true))
    expect(await screen.findByText('whsec_new')).toBeInTheDocument()
  })
})

describe('stripe', () => {
  it('shows the connection, events and incidents, and retries an event', async () => {
    let retried = false
    server.use(
      http.get('http://console.test/api/v1/stripe/configuration', () => HttpResponse.json(configuration)),
      http.get('http://console.test/api/v1/stripe/events', () =>
        HttpResponse.json({ data: [event], page: { limit: 20, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/api/v1/stripe/incidents', () =>
        HttpResponse.json({ data: [], page: { limit: 20, offset: 0, total: 0 } }),
      ),
      http.get('http://console.test/api/v1/portal/return-urls', () =>
        HttpResponse.json(['https://example.com/return']),
      ),
      http.post('http://console.test/api/v1/stripe/events/ev1/retry', () => {
        retried = true
        return HttpResponse.json(event)
      }),
    )
    const actor = userEvent.setup()
    renderPage(() => <StripePage />, { permissions: allBillingPermissions })
    expect(await screen.findByText('acct_1')).toBeInTheDocument()
    expect(await screen.findByText('invoice.paid')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: 'Retry event' }))
    const dialog = await screen.findByRole('alertdialog')
    await actor.click(within(dialog).getByRole('button', { name: 'Retry event' }))
    await waitFor(() => expect(retried).toBe(true))
  })

  it('offers only the two ways to connect while the tenant is not connected', async () => {
    server.use(
      http.get('http://console.test/api/v1/stripe/configuration', () =>
        HttpResponse.json({ title: 'Not Found', status: 404 }, { status: 404 }),
      ),
    )
    renderPage(() => <StripePage />, { permissions: allBillingPermissions })
    expect(await screen.findByText('Connect a Stripe account')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Start onboarding' }).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Disconnect' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reconcile' })).not.toBeInTheDocument()
  })

  it('renders in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/api/v1/stripe/configuration', () => HttpResponse.json(configuration)),
      http.get('http://console.test/api/v1/stripe/events', () =>
        HttpResponse.json({ data: [], page: { limit: 20, offset: 0, total: 0 } }),
      ),
      http.get('http://console.test/api/v1/stripe/incidents', () =>
        HttpResponse.json({ data: [], page: { limit: 20, offset: 0, total: 0 } }),
      ),
      http.get('http://console.test/api/v1/portal/return-urls', () => HttpResponse.json([])),
    )
    renderPage(() => <StripePage />, { permissions: allBillingPermissions, locale: 'vi' })
    expect(await screen.findByRole('heading', { name: 'Stripe' })).toBeInTheDocument()
  })
})
