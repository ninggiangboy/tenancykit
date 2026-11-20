import { describe, expect, it } from 'vitest'
import { appEntry } from './app-catalog'

describe('the app catalogue', () => {
  it('lists the screens Billing offers from Home', () => {
    expect(appEntry('billing', 'TENANT')?.shortcuts.map((shortcut) => shortcut.path)).toEqual([
      'invoices',
      'payments',
      'customers',
      'usage',
    ])
  })

  it('shows Tenants only on the Platform shell', () => {
    const tenantPaths = appEntry('platform', 'TENANT')?.shortcuts.map((shortcut) => shortcut.path) ?? []
    const platformPaths = appEntry('platform', 'PLATFORM')?.shortcuts.map((shortcut) => shortcut.path) ?? []
    expect(tenantPaths).not.toContain('tenants')
    expect(platformPaths).toContain('tenants')
  })

  it('has nothing for an app it does not know', () => {
    expect(appEntry('unknown', 'TENANT')).toBeUndefined()
  })
})
