import { buildRemote, publishRemote, versionFor } from '../support/deploy'
import { env, expect, mountedVersion, openRemote, sidebarApps, signIn, test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { findRemote } from '../support/platform'
import { ensureRemoteActive, platformAdminToken, provisionTenantFixture, waitForBilling } from '../support/stack'

const BILLING_ROW = (page: import('@playwright/test').Page) =>
  page.getByRole('row').filter({ has: page.getByText('billing', { exact: true }) })

/**
 * The operator's two controls: a remote switched off stops loading after a reload, and pointing the remote at another
 * registered version is a rollback that takes a reload, never a build. Both are measured against the clock.
 */
test('the operator disables a remote and rolls back to an older version without a build', async ({ browser, page }) => {
  test.setTimeout(600_000)

  const tenant = await provisionTenantFixture({ billing: true, prefix: 'e2e-toggle' })
  await waitForBilling(tenant.host, tenant.token)
  const baseline = await ensureRemoteActive('billing')
  const activeVersion = baseline.activeVersion
  if (!activeVersion) throw new Error('billing has no active version after the baseline')

  const tenantContext = await browser.newContext()
  const tenantPage = await tenantContext.newPage()
  const tenantHealth = watchConsole(tenantPage)
  await signIn(tenantPage, tenant.host, tenant.adminEmail, tenant.adminPassword)
  expect(await sidebarApps(tenantPage)).toContain('Billing')

  await signIn(page, env.platformHost, env.platformAdmin.email, env.platformAdmin.password)
  await openRemote(page, env.platformHost, 'platform', 'remotes')

  // Off: the switch is optimistic, and the toast confirms the server took it.
  const toggleStarted = Date.now()
  await BILLING_ROW(page).getByRole('switch', { name: 'Enable remote' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Remote disabled' })).toBeVisible()

  await tenantPage.reload()
  await expect.poll(async () => sidebarApps(tenantPage), { timeout: 60_000 }).not.toContain('Billing')
  expect(Date.now() - toggleStarted).toBeLessThan(60_000)

  // On again: the same version is still the active one, so the menu returns as it was.
  await BILLING_ROW(page).getByRole('switch', { name: 'Enable remote' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Remote enabled' })).toBeVisible()
  await tenantPage.reload()
  await expect.poll(async () => sidebarApps(tenantPage), { timeout: 60_000 }).toContain('Billing')

  // A new version is published but not active; the operator activates it, then rolls back to the previous one.
  const next = versionFor('rollback')
  buildRemote('billing', next)
  publishRemote('billing', next)

  await page.reload()
  await openRemote(page, env.platformHost, 'platform', 'remotes')
  await BILLING_ROW(page).click()
  const sheet = page.getByRole('dialog')
  await sheet.locator('li').filter({ hasText: next }).getByRole('button', { name: 'Activate', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: next })).toBeVisible()

  await openRemote(tenantPage, tenant.host, 'billing', 'customers')
  await expect.poll(async () => mountedVersion(tenantPage, 'billing'), { timeout: 60_000 }).toBe(next)

  // Rollback: the same click on the version that was live before, and no build runs for it.
  const rollbackStarted = Date.now()
  await page.reload()
  await openRemote(page, env.platformHost, 'platform', 'remotes')
  await BILLING_ROW(page).click()
  await page
    .getByRole('dialog')
    .locator('li')
    .filter({ hasText: activeVersion })
    .getByRole('button', { name: 'Activate', exact: true })
    .click()
  await expect(page.getByRole('status').filter({ hasText: activeVersion })).toBeVisible()

  await openRemote(tenantPage, tenant.host, 'billing', 'customers')
  await expect.poll(async () => mountedVersion(tenantPage, 'billing'), { timeout: 60_000 }).toBe(activeVersion)
  expect(Date.now() - rollbackStarted).toBeLessThan(60_000)

  const registry = await findRemote(await platformAdminToken(), 'billing')
  expect(registry.activeVersion).toBe(activeVersion)
  await tenantHealth.expectClean()
  await tenantContext.close()
})
