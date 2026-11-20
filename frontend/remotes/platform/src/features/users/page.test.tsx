import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { allPlatformPermissions, renderPage } from '../../test-utils'
import { UsersPage } from './page'

const server = setupServer()
beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const user = {
  id: 'u1',
  tenantId: 't1',
  scope: 'TENANT',
  email: 'ana@example.com',
  displayName: 'Ana',
  status: 'ACTIVE',
  version: 1,
}

describe('users page', () => {
  it('lists users', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/users', () =>
        HttpResponse.json({ data: [user], page: { limit: 50, offset: 0, total: 1 } }),
      ),
    )
    renderPage((state) => <UsersPage state={state} />, { permissions: allPlatformPermissions })
    expect(await screen.findByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('ana@example.com')).toBeInTheDocument()
  })

  it('creates a user through the dialog', async () => {
    let body: unknown
    server.use(
      http.get('http://console.test/platform/api/v1/users', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
      http.post('http://console.test/platform/api/v1/users', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(user, { status: 201 })
      }),
    )
    const actor = userEvent.setup()
    renderPage((state) => <UsersPage state={state} />, { permissions: allPlatformPermissions })

    // The page names its one primary action; the header renders it first, then the phone's floating copy.
    await actor.click((await screen.findAllByRole('button', { name: 'Create user' }))[0] as HTMLElement)
    await actor.type(screen.getByLabelText('Display name'), 'Ana')
    await actor.type(screen.getByLabelText('Email'), 'ana@example.com')
    await actor.type(screen.getByLabelText('Temporary password'), 'change-me-1')
    await actor.click(screen.getByRole('button', { name: 'Create', hidden: true }))

    await waitFor(() => expect(body).toBeTruthy())
    expect(body).toMatchObject({ displayName: 'Ana', email: 'ana@example.com' })
    expect(await screen.findByText('User created')).toBeInTheDocument()
  })

  it('hides create from a read-only caller', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/users', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <UsersPage state={state} />, { permissions: ['platform:user:read'] })
    expect(await screen.findByRole('heading', { name: 'Users' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create user' })).not.toBeInTheDocument()
  })

  it('renders in Vietnamese', async () => {
    server.use(
      http.get('http://console.test/platform/api/v1/users', () =>
        HttpResponse.json({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
      ),
    )
    renderPage((state) => <UsersPage state={state} />, {
      permissions: allPlatformPermissions,
      locale: 'vi',
    })
    expect(await screen.findByRole('heading', { name: 'Người dùng' })).toBeInTheDocument()
  })
})
