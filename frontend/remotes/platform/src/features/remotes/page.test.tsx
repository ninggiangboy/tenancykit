import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allPlatformPermissions, renderPage } from '../../test-utils'
import { RemotesPage } from './page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const remote = {
  name: 'billing',
  appCode: 'billing',
  title: 'Billing',
  navOrder: 20,
  audiences: ['TENANT'],
  enabled: true,
  activeVersion: '1.0.0',
  createdAt: '2026-11-17T09:00:00Z',
  version: 4,
  versions: [
    { version: '1.0.0', entryPath: '/console/remotes/billing/1.0.0/mf-manifest.json', sdkMajor: 1 },
    { version: '1.0.1', entryPath: '/console/remotes/billing/1.0.1/mf-manifest.json', sdkMajor: 1 },
  ],
}

describe('ui remotes page', () => {
  it('activates a registered version', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/platform/api/v1/ui-remotes', () =>
        HttpResponse.json({ data: [remote], page: { limit: 50, offset: 0, total: 1 } }),
      ),
      http.put('http://console.test/platform/api/v1/ui-remotes/billing', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ ...remote, activeVersion: '1.0.1', version: 5 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <RemotesPage state={state} />, { permissions: allPlatformPermissions })

    await actor.click(await screen.findByText('Billing'))
    await actor.click(await screen.findByRole('button', { name: 'Activate' }))

    // Activation keeps the remote's enabled state; enabling is a separate write.
    await waitFor(() => expect(body).toEqual({ enabled: true, activeVersion: '1.0.1' }))
    expect(await screen.findByText('billing now serves 1.0.1')).toBeInTheDocument()
  })

  it('hides activation from a read-only caller', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/ui-remotes', () =>
        HttpResponse.json({ data: [remote], page: { limit: 50, offset: 0, total: 1 } }),
      ),
    )
    const actor = userEvent.setup()
    renderPage((state) => <RemotesPage state={state} />, { permissions: ['platform:ui-remote:read'] })

    await actor.click(await screen.findByText('Billing'))
    expect(await screen.findByText('1.0.1')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('renders in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/ui-remotes', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <RemotesPage state={state} />, {
      permissions: allPlatformPermissions,
      locale: 'vi',
    })
    expect(await screen.findByRole('heading', { name: 'UI remote' })).toBeInTheDocument()
  })
})
