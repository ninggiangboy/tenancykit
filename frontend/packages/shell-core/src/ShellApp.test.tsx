import { useShell } from '@suite/shell-sdk/react'
import { createBrowserHistory } from '@tanstack/react-router'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ConsoleRemote, MeResponse } from './contracts'
import type { RemoteLoader } from './remote-loader.types'
import { ShellApp } from './ShellApp'

const me: MeResponse = {
  id: 'u1',
  tenantId: 't1',
  scope: 'TENANT',
  email: 'ada@acme.test',
  displayName: 'Ada',
  status: 'ACTIVE',
  permissions: ['billing:invoice:read'],
}

const remotes: ConsoleRemote[] = [
  {
    name: 'platform',
    title: 'Platform',
    appCode: 'platform',
    navOrder: 10,
    version: '1',
    entryPath: '/p',
    integrity: 'i',
    sdkMajor: 1,
  },
  {
    name: 'billing',
    title: 'Billing',
    appCode: 'billing',
    navOrder: 20,
    version: '1',
    entryPath: '/b',
    integrity: 'i',
    sdkMajor: 1,
  },
]

function loader(load: RemoteLoader['load']): RemoteLoader {
  return { load }
}

const Root = () => <p>Billing invoices</p>

/** The sidebar's app list; the home page links the same apps as cards. */
const apps = () => within(screen.getByRole('navigation', { name: 'Apps' }))
const appLink = async (name: RegExp) => {
  await screen.findByRole('navigation', { name: 'Apps' })
  return apps().findByRole('link', { name })
}

function mount(load: RemoteLoader['load'] = async () => ({ name: 'billing', sdkMajor: 1, Root })) {
  globalThis.history.replaceState({}, '', '/console/')
  return render(
    <ShellApp
      definition={{ audience: 'TENANT', title: 'Console' }}
      me={me}
      remotes={remotes}
      history={createBrowserHistory()}
      loader={loader(load)}
      getToken={async () => 'token'}
    />,
  )
}

/** A remote that registers one section, the way remote-kit's navigation does. */
function Sections() {
  const ctx = useShell()
  useEffect(
    () =>
      ctx.registerNavigation?.([
        { id: 'money', label: 'Money', items: [{ id: 'invoices', label: 'Invoices', to: '/invoices' }] },
      ]),
    [ctx],
  )
  return <p>Billing invoices</p>
}

describe('the shell frame', () => {
  it('shows one navigation item per remote and the caller’s name', async () => {
    mount()

    expect(await appLink(/Billing/)).toBeInTheDocument()
    expect(apps().getByRole('link', { name: /Platform/ })).toBeInTheDocument()
    expect(screen.getAllByText('Ada').length).toBeGreaterThan(0)
  })

  it('opens the palette from the keyboard and offers the remotes', async () => {
    mount()
    await appLink(/Billing/)

    await userEvent.keyboard('{Control>}k{/Control}')

    expect(await screen.findByPlaceholderText('Search or jump to')).toBeInTheDocument()
    expect(screen.getAllByText('Billing').length).toBeGreaterThan(1)
  })

  it('mounts a remote through the shell context', async () => {
    mount()

    await userEvent.click(await appLink(/Billing/))

    expect(await screen.findByText('Billing invoices')).toBeInTheDocument()
  })

  it('shows an error panel with a retry when a remote fails, never a blank page', async () => {
    const load = vi.fn(async () => {
      throw new Error('Failed to get manifest')
    })
    mount(load)

    await userEvent.click(await appLink(/Billing/))

    expect(await screen.findByText('This app could not load')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('lists the sections a remote registers under its app and in the breadcrumb', async () => {
    globalThis.history.replaceState({}, '', '/console/r/billing/invoices')
    render(
      <ShellApp
        definition={{ audience: 'TENANT', title: 'Console' }}
        me={me}
        remotes={remotes}
        history={createBrowserHistory()}
        loader={loader(async () => ({ name: 'billing', sdkMajor: 1, Root: Sections }))}
        getToken={async () => 'token'}
      />,
    )

    await screen.findByText('Billing invoices')
    expect(await apps().findByRole('link', { name: 'Invoices' })).toHaveAttribute('aria-current', 'page')
    expect(within(screen.getByRole('navigation', { name: 'Breadcrumb' })).getByText('Invoices')).toBeInTheDocument()
  })

  it('keeps one history when the caller passes none, so a remote that registers its nav mounts once', async () => {
    globalThis.history.replaceState({}, '', '/console/r/billing/invoices')
    const load = vi.fn(async () => ({ name: 'billing', sdkMajor: 1, Root: Sections }))
    render(
      <ShellApp
        definition={{ audience: 'TENANT', title: 'Console' }}
        me={me}
        remotes={remotes}
        loader={loader(load)}
        getToken={async () => 'token'}
      />,
    )

    await screen.findByText('Billing invoices')
    expect(await apps().findByRole('link', { name: 'Invoices' })).toBeInTheDocument()
    expect(screen.getAllByText('Billing invoices')).toHaveLength(1)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('says a tenant with no apps has none, instead of an empty frame', async () => {
    globalThis.history.replaceState({}, '', '/console/')
    render(
      <ShellApp
        definition={{ audience: 'TENANT', title: 'Console' }}
        me={me}
        remotes={[]}
        history={createBrowserHistory()}
        loader={loader(async () => ({ name: 'billing', sdkMajor: 1, Root }))}
        getToken={async () => 'token'}
      />,
    )

    expect(await screen.findByText('No apps are enabled for this tenant')).toBeInTheDocument()
  })
})
