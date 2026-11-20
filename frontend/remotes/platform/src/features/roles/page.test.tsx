import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allPlatformPermissions, renderPage } from '../../test-utils'
import { RolesPage } from './page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const role = {
  id: 'r1',
  tenantId: null,
  scope: 'PLATFORM',
  name: 'Billing operator',
  system: false,
  permissions: ['billing:invoice:read'],
  version: 1,
}

describe('roles page', () => {
  it('lists roles and saves an edited permission set', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/platform/api/v1/roles', () =>
        HttpResponse.json({ data: [role], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.put('http://console.test/platform/api/v1/roles/r1/permissions', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ ...role, permissions: ['billing:invoice:read', 'billing:invoice:manage'] })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <RolesPage state={state} />, { permissions: allPlatformPermissions })

    await actor.click(await screen.findByText('Billing operator'))
    await actor.click(await screen.findByLabelText('Add permission'))
    await actor.type(await screen.findByPlaceholderText('Search'), 'billing:invoice:manage')
    await actor.click(await screen.findByRole('option', { name: 'billing:invoice:manage' }))
    await actor.click(screen.getByRole('button', { name: /Save/ }))

    await waitFor(() => expect(body).toBeTruthy())
    expect(body).toEqual({ permissions: ['billing:invoice:read', 'billing:invoice:manage'] })
    expect(await screen.findByText('Role saved')).toBeInTheDocument()
  })

  it('shows permission codes read-only without the manage permission', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/roles', () =>
        HttpResponse.json({ data: [role], page: { limit: 50, offset: 0, total: 1 } }),
      ),
    )
    const actor = userEvent.setup()
    renderPage((state) => <RolesPage state={state} />, { permissions: ['platform:role:read'] })

    await actor.click(await screen.findByText('Billing operator'))
    expect(await screen.findByText('billing:invoice:read')).toBeInTheDocument()
    expect(screen.queryByLabelText('Permissions')).not.toBeInTheDocument()
  })

  it('renders in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/roles', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <RolesPage state={state} />, {
      permissions: allPlatformPermissions,
      locale: 'vi',
    })
    expect(await screen.findByRole('heading', { name: 'Vai trò' })).toBeInTheDocument()
  })
})
