import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allPlatformPermissions, renderPage } from '../../test-utils'
import { SystemsPage } from './page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const system = {
  id: 's1',
  name: 'Booking engine',
  purpose: 'Creates charges',
  status: 'ACTIVE',
  createdAt: '2026-11-17T09:00:00Z',
  version: 1,
}

describe('tenant systems page', () => {
  it('issues a credential and shows its secret once', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/platform/api/v1/tenant-systems', () =>
        HttpResponse.json({ data: [system], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/platform/api/v1/tenant-systems/s1/credentials', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
      http.post('http://console.test/platform/api/v1/tenant-systems/s1/credentials', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          {
            credential: { id: 'c1', keyId: 'key-1', label: 'CI', status: 'ACTIVE' },
            key: 'sk_live_secret',
          },
          { status: 201 },
        )
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <SystemsPage state={state} />, { permissions: allPlatformPermissions })

    await actor.click(await screen.findByText('Booking engine'))
    await actor.click(await screen.findByRole('button', { name: 'Issue credential' }))
    await actor.type(screen.getByLabelText('Label'), 'CI')
    await actor.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(body).toEqual({ label: 'CI', scopes: [] }))
    expect(await screen.findByText('sk_live_secret')).toBeInTheDocument()
  })

  it('hides credential controls without the permission', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/tenant-systems', () =>
        HttpResponse.json({ data: [system], page: { limit: 50, offset: 0, total: 1 } }),
      ),
    )
    const actor = userEvent.setup()
    renderPage((state) => <SystemsPage state={state} />, { permissions: ['platform:tenant-system:read'] })

    await actor.click(await screen.findByText('Booking engine'))
    expect(await screen.findByText('Credentials')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Issue credential' })).not.toBeInTheDocument()
  })

  it('renders in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/tenant-systems', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <SystemsPage state={state} />, {
      permissions: allPlatformPermissions,
      locale: 'vi',
    })
    expect(await screen.findByRole('heading', { name: 'Hệ thống tenant' })).toBeInTheDocument()
  })
})
