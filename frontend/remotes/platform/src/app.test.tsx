import { createRemoteRouter, RemoteProvider } from '@suite/remote-kit'
import type { ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { createI18n } from './i18n'
import { screens, sections } from './routes'

const server = setupServer(
  http.get('http://console.test/platform/api/v1/tenants', () =>
    HttpResponse.json({
      data: [{ id: 't1', code: 'acme', name: 'Acme', status: 'ACTIVE', createdAt: '2026-11-17T09:00:00Z' }],
      page: { limit: 50, offset: 0, total: 1 },
    }),
  ),
  http.get('http://console.test/platform/api/v1/tenants/t1/apps', () => HttpResponse.json([])),
)
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const context: ShellContext = {
  basepath: '/console/r/platform',
  history: createMemoryHistory({ initialEntries: ['/console/r/platform/tenants'] }),
  getToken: async () => 'test-token',
  user: {
    id: 'u1',
    email: 'operator@example.com',
    displayName: 'Operator',
    scope: 'PLATFORM',
    tenantId: null,
    permissions: ['platform:tenant:read'],
  },
  tenant: null,
  theme: 'light',
  locale: 'en',
  navigate: () => {},
  registerCommands: () => () => {},
}

describe('platform remote', () => {
  it('mounts on the shell history and opens the tenants section', async () => {
    render(
      <ShellProvider ctx={context}>
        <RemoteProvider ctx={context} createI18n={createI18n} resolveBaseUrl={() => 'http://console.test/platform'}>
          <RouterProvider router={createRemoteRouter(context, screens, sections)} />
        </RemoteProvider>
      </ShellProvider>,
    )
    expect(await screen.findByRole('heading', { name: 'Tenants' })).toBeInTheDocument()
  })

  it('keeps the route and puts the open record in the URL', async () => {
    const actor = userEvent.setup()
    render(
      <ShellProvider ctx={context}>
        <RemoteProvider ctx={context} createI18n={createI18n} resolveBaseUrl={() => 'http://console.test/platform'}>
          <RouterProvider router={createRemoteRouter(context, screens, sections)} />
        </RemoteProvider>
      </ShellProvider>,
    )
    await actor.click(await screen.findByText('Acme'))
    await waitFor(() => expect(context.history.location.search).toContain('open=t1'))
    expect(context.history.location.pathname).toBe('/console/r/platform/tenants')
  })
})
