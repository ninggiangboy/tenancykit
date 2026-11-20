import type { AppCode } from '@suite/ui'
import type { ShellMessageKey } from '../i18n'

export interface AppEntry {
  purpose: ShellMessageKey
  description: ShellMessageKey
  shortcuts: Array<{ label: ShellMessageKey; path: string }>
}

/** What each app is for and which of its screens Home offers. The next app adds one entry here. */
export const appCatalog: Partial<Record<AppCode, AppEntry>> = {
  billing: {
    purpose: 'apps.billing.purpose',
    description: 'apps.billing.description',
    shortcuts: [
      { label: 'apps.billing.invoices', path: 'invoices' },
      { label: 'apps.billing.payments', path: 'payments' },
      { label: 'apps.billing.customers', path: 'customers' },
      { label: 'apps.billing.usage', path: 'usage' },
    ],
  },
  platform: {
    purpose: 'apps.platform.purpose',
    description: 'apps.platform.description',
    shortcuts: [
      { label: 'apps.platform.users', path: 'users' },
      { label: 'apps.platform.roles', path: 'roles' },
      { label: 'apps.platform.tenants', path: 'tenants' },
    ],
  },
}

/** The catalogue entry for an app; tenant screens such as Tenants appear only on the Platform shell. */
export function appEntry(appCode: string, audience: 'PLATFORM' | 'TENANT'): AppEntry | undefined {
  const entry = appCatalog[appCode as AppCode]
  if (!entry) return undefined
  if (appCode !== 'platform') return entry
  return {
    ...entry,
    shortcuts: entry.shortcuts.filter((shortcut) => shortcut.path !== 'tenants' || audience === 'PLATFORM'),
  }
}
