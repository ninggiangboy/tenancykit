import type { ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createRemoteI18n } from '../i18n/create-remote-i18n'
import { RemoteProvider } from '../runtime/remote-provider'
import { createRemoteRouter, type Screen } from './remote-router'

const resources = {
  en: { common: { 'nav.a': 'Group' }, one: { title: 'First' }, two: { title: 'Second' } },
  vi: { common: { 'nav.a': 'Nhóm' }, one: { title: 'Một' }, two: { title: 'Hai' } },
}

const screens: Screen[] = [
  { id: 'one', path: '/one', section: 'a', label: 'one:title', permission: 'x:one', component: () => <p>one body</p> },
  { id: 'two', path: '/two', section: 'a', label: 'two:title', permission: 'x:two', component: () => <p>two body</p> },
]

function mount(path: string, permissions: string[]) {
  const ctx: ShellContext = {
    basepath: '/r',
    history: createMemoryHistory({ initialEntries: [path] }),
    getToken: async () => 't',
    user: { id: 'u', email: 'a@b.c', displayName: 'A', scope: 'TENANT', tenantId: 't', permissions },
    tenant: null,
    theme: 'light',
    locale: 'en',
    navigate: () => {},
    registerCommands: () => () => {},
  }
  const router = createRemoteRouter(ctx, screens, [{ id: 'a', label: 'common:nav.a' }])
  return render(
    <ShellProvider ctx={ctx}>
      <RemoteProvider ctx={ctx} createI18n={(locale) => createRemoteI18n(locale, resources)}>
        <RouterProvider router={router} />
      </RemoteProvider>
    </ShellProvider>,
  )
}

describe('createRemoteRouter', () => {
  it('lands on the first screen the caller may open', async () => {
    mount('/r', ['x:two'])
    expect(await screen.findByText('two body')).toBeInTheDocument()
  })

  it('keeps the screen header and says so when the caller lacks its permission', async () => {
    mount('/r/two', ['x:one'])
    expect(await screen.findByRole('heading', { name: 'Second', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('You do not have permission to view this page.')).toBeInTheDocument()
    expect(screen.queryByText('two body')).not.toBeInTheDocument()
  })

  it('lists only the screens the caller may open in its own nav when the shell takes none', async () => {
    mount('/r/one', ['x:one'])
    expect(await screen.findByText('one body')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'First' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Second' })).not.toBeInTheDocument()
  })
})
