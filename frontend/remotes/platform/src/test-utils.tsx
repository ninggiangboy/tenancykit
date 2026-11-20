import { RemoteProvider } from '@suite/remote-kit'
import type { Locale, ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { type SearchRecord, type UrlState, urlState } from '@suite/ui'
import { createBrowserHistory } from '@tanstack/react-router'
import { type RenderResult, render } from '@testing-library/react'
import { type ReactNode, useState } from 'react'
import { createI18n } from './i18n'

export function shellContext(options: { permissions?: string[]; locale?: Locale } = {}): ShellContext {
  return {
    basepath: '/console/r/platform',
    history: createBrowserHistory(),
    getToken: async () => 'test-token',
    user: {
      id: 'u1',
      email: 'operator@example.com',
      displayName: 'Operator',
      scope: 'PLATFORM',
      tenantId: null,
      permissions: options.permissions ?? [],
    },
    tenant: null,
    theme: 'light',
    locale: options.locale ?? 'en',
    navigate: () => {},
    registerCommands: () => () => {},
  }
}

function Harness({ initial, children }: { initial: SearchRecord; children: (state: UrlState) => ReactNode }) {
  const [search, setSearch] = useState<SearchRecord>(initial)
  return <>{children(urlState(search, setSearch))}</>
}

export interface RenderPageOptions {
  permissions?: string[]
  locale?: Locale
  search?: SearchRecord
}

/** Renders a page with the shell context, the API clients and a reactive URL state a test can read back. */
export function renderPage(
  page: (state: UrlState) => ReactNode,
  options: RenderPageOptions = {},
): RenderResult & { ctx: ShellContext } {
  const ctx = shellContext(options)
  const result = render(
    <ShellProvider ctx={ctx}>
      <RemoteProvider ctx={ctx} createI18n={createI18n} resolveBaseUrl={() => 'http://console.test/platform'}>
        <Harness initial={options.search ?? {}}>{page}</Harness>
      </RemoteProvider>
    </ShellProvider>,
  )
  return { ...result, ctx }
}

export const allPlatformPermissions = [
  'platform:tenant:create',
  'platform:tenant:rename',
  'platform:tenant:read',
  'platform:tenant:deactivate',
  'platform:tenant-domain:manage',
  'platform:entitlement:manage',
  'platform:user:read',
  'platform:user:manage',
  'platform:role:read',
  'platform:role:manage',
  'platform:tenant-system:read',
  'platform:tenant-system:manage',
  'platform:credential:manage',
  'platform:ui-remote:read',
  'platform:ui-remote:manage',
]
