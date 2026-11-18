import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allPlatformPermissions, renderPage } from '../../test-utils'
import { TenantsPage } from './page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const tenant = { id: 't1', code: 'acme', name: 'Acme', status: 'ACTIVE', createdAt: '2026-11-17T09:00:00Z', version: 3 }

describe('tenants page', () => {
  it('lists tenants and opens the record sheet with its app entitlements', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/tenants', () =>
        HttpResponse.json({ data: [tenant], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/platform/api/v1/tenants/t1/apps', () =>
        HttpResponse.json([{ appCode: 'billing', enabled: true, version: 1 }]),
      ),
    )
    const user = userEvent.setup()
    renderPage((state) => <TenantsPage state={state} />, { permissions: allPlatformPermissions })

    expect(await screen.findByText('Acme')).toBeInTheDocument()
    await user.click(screen.getByText('Acme'))

    expect(await screen.findByText('App entitlements')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Billing' })).toBeChecked()
  })

  it('toggles an app and reports the change in a toast', async () => {
    const calls: string[] = []
    server.use(
      http.get('http://console.test/platform/api/v1/tenants', () =>
        HttpResponse.json({ data: [tenant], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/platform/api/v1/tenants/t1/apps', () =>
        HttpResponse.json([{ appCode: 'billing', enabled: true, version: 1 }]),
      ),
      http.put('http://console.test/platform/api/v1/tenants/t1/apps/billing', async ({ request }) => {
        calls.push(await request.text())
        return HttpResponse.json({ tenantId: 't1', appCode: 'billing', enabled: false, version: 2 })
      }),
    )
    const user = userEvent.setup()
    renderPage((state) => <TenantsPage state={state} />, { permissions: allPlatformPermissions })

    await user.click(await screen.findByText('Acme'))
    const billing = await screen.findByRole('switch', { name: 'Billing' })
    await user.click(billing)

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(JSON.parse(calls[0] ?? '{}')).toEqual({ enabled: false })
    expect(await screen.findByText('Billing disabled for acme')).toBeInTheDocument()
  })

  it('hides the entitlement switch from a caller without the manage permission', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/tenants', () =>
        HttpResponse.json({ data: [tenant], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.get('http://console.test/platform/api/v1/tenants/t1/apps', () =>
        HttpResponse.json([{ appCode: 'billing', enabled: true, version: 1 }]),
      ),
    )
    const user = userEvent.setup()
    renderPage((state) => <TenantsPage state={state} />, {
      permissions: ['platform:tenant:read'],
    })

    await user.click(await screen.findByText('Acme'))
    expect(await screen.findByText('App entitlements')).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'Billing' })).not.toBeInTheDocument()
  })

  it('renders its title in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/tenants', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <TenantsPage state={state} />, {
      permissions: allPlatformPermissions,
      locale: 'vi',
    })
    expect(await screen.findByRole('heading', { name: 'Tenant' })).toBeInTheDocument()
  })
})
