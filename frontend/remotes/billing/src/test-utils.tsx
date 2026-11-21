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
    basepath: '/console/r/billing',
    history: createBrowserHistory(),
    getToken: async () => 'test-token',
    user: {
      id: 'u1',
      email: 'admin@example.com',
      displayName: 'Admin',
      scope: 'TENANT',
      tenantId: 't1',
      permissions: options.permissions ?? [],
    },
    tenant: { id: 't1', code: 'acme', name: 'Acme' },
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
      <RemoteProvider ctx={ctx} createI18n={createI18n} resolveBaseUrl={() => 'http://console.test'}>
        <Harness initial={options.search ?? {}}>{page}</Harness>
      </RemoteProvider>
    </ShellProvider>,
  )
  return { ...result, ctx }
}

export const allBillingPermissions = [
  'billing:customer:read',
  'billing:customer:write',
  'billing:payment:read',
  'billing:payment:create',
  'billing:refund:create',
  'billing:invoice:read',
  'billing:invoice:create',
  'billing:invoice:manage',
  'billing:subscription:read',
  'billing:subscription:manage',
  'billing:pricing:read',
  'billing:pricing:manage',
  'billing:coupon:read',
  'billing:coupon:manage',
  'billing:coupon:redeem',
  'billing:wallet:read',
  'billing:wallet:manage',
  'billing:reservation:manage',
  'billing:ledger:reverse',
  'billing:charge:create',
  'billing:usage:read',
  'billing:usage:record',
  'billing:webhook:read',
  'billing:webhook:manage',
  'billing:stripe:read',
  'billing:stripe:manage',
  'billing:portal-settings:manage',
]

export const ids = {
  customer: '00000000-0000-4000-8000-0000000000c1',
  wallet: '00000000-0000-4000-8000-0000000000a1',
  plan: '00000000-0000-4000-8000-0000000000b1',
  coupon: '00000000-0000-4000-8000-0000000000d1',
  charge: '00000000-0000-4000-8000-0000000000e1',
}

type Actor = ReturnType<typeof import('@testing-library/user-event').default.setup>

/** Opens the combobox with this label and picks the option whose text matches. */
export async function pick(actor: Actor, label: string, option: string | RegExp): Promise<void> {
  const { screen } = await import('@testing-library/react')
  await actor.click(await screen.findByLabelText(label))
  await actor.click(await screen.findByRole('option', { name: option }))
}

/** A `Money` amount reads as one string, though its currency code is its own element: match the money element. */
export const moneyText =
  (text: string) =>
  (_: string, element: Element | null): boolean =>
    element?.hasAttribute('data-currency') === true && element.textContent === text
