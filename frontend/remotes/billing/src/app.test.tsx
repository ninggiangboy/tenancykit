import { createRemoteRouter, RemoteProvider } from '@suite/remote-kit'
import type { ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { createI18n } from './i18n'
import { screens, sections } from './routes'

const server = setupServer(
  http.get('http://console.test/api/v1/customers', () =>
    HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
  ),
)
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const context: ShellContext = {
  basepath: '/console/r/billing',
  history: createMemoryHistory({ initialEntries: ['/console/r/billing/customers'] }),
  getToken: async () => 'test-token',
  user: {
    id: 'u1',
    email: 'admin@example.com',
    displayName: 'Admin',
    scope: 'TENANT',
    tenantId: 't1',
    permissions: ['billing:customer:read'],
  },
  tenant: { id: 't1', code: 'acme', name: 'Acme' },
  theme: 'light',
  locale: 'en',
  navigate: () => {},
  registerCommands: () => () => {},
}

describe('billing remote', () => {
  it('mounts on the shell history and opens the customers section', async () => {
    render(
      <ShellProvider ctx={context}>
        <RemoteProvider ctx={context} createI18n={createI18n} resolveBaseUrl={() => 'http://console.test'}>
          <RouterProvider router={createRemoteRouter(context, screens, sections)} />
        </RemoteProvider>
      </ShellProvider>,
    )
    expect(await screen.findByRole('heading', { name: 'Customers' })).toBeInTheDocument()
  })
})
