import { buildRemote, publishRemote, shellAssetUrls, versionFor } from '../support/deploy'
import { env, expect, mountedVersion, openRemote, sidebarApps, signIn, test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { ensureRemoteActive, platformAdminToken, provisionTenantFixture, waitForBilling } from '../support/stack'

/**
 * Deploying a remote alone is a deploy: a new version is published and the operator makes it live in Platform Admin,
 * and the Tenant Shell picks it up on the next load while the shell's own assets stay byte-identical.
 */
test('a new remote-billing version goes live with no shell build', async ({ browser, page }) => {
  test.setTimeout(600_000)

  const tenant = await provisionTenantFixture({ billing: true, prefix: 'e2e-deploy' })
  await waitForBilling(tenant.host, tenant.token)
  await ensureRemoteActive('billing')
  const shellBefore = await shellAssetUrls(tenant.host)

  const version = versionFor('deploy')
  buildRemote('billing', version)
  publishRemote('billing', version)

  const operator = await platformAdminToken()
  expect(operator).not.toBe('')

  // The operator activates the version in Platform Admin, which is what makes a deploy live.
  await signIn(page, env.platformHost, env.platformAdmin.email, env.platformAdmin.password)
  await openRemote(page, env.platformHost, 'platform', 'remotes')
  await page
    .getByRole('row')
    .filter({ has: page.getByText('billing', { exact: true }) })
    .click()
  const sheet = page.getByRole('dialog')
  await expect(sheet.getByRole('heading', { name: 'Billing' })).toBeVisible()
  await sheet.locator('li').filter({ hasText: version }).getByRole('button', { name: 'Activate', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: version })).toBeVisible()

  // A Tenant's people, in their own session, see the version the operator just activated.
  const tenantContext = await browser.newContext()
  const tenantPage = await tenantContext.newPage()
  const tenantHealth = watchConsole(tenantPage)
  await signIn(tenantPage, tenant.host, tenant.adminEmail, tenant.adminPassword)
  expect(await sidebarApps(tenantPage)).toContain('Billing')
  await openRemote(tenantPage, tenant.host, 'billing', 'customers')
  expect(await mountedVersion(tenantPage, 'billing')).toBe(version)
  await expect(tenantPage.getByRole('heading', { name: 'Customers' })).toBeVisible()

  // Nothing in the shell changed: the same HTML names the same assets it named before the deploy.
  expect(await shellAssetUrls(tenant.host)).toEqual(shellBefore)
  await tenantHealth.expectClean()
  await tenantContext.close()
})
