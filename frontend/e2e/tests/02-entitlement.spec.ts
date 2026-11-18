import { expect, openRemote, sidebarApps, signIn, test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { setTenantApp } from '../support/platform'
import { platformAdminToken, provisionTenantFixture, waitForBilling } from '../support/stack'

/**
 * A tenant's console shows the apps its entitlement allows, and the backend is what decides: the menu follows the
 * entitlement as soon as the shell reloads, and Billing itself refuses a tenant that has it turned off.
 */
test('the Billing menu follows the tenant entitlement', async ({ browser, page, health }) => {
  test.setTimeout(300_000)

  const withBilling = await provisionTenantFixture({ billing: true, prefix: 'e2e-ent' })
  const withoutBilling = await provisionTenantFixture({ billing: false, prefix: 'e2e-ent' })
  await waitForBilling(withBilling.host, withBilling.token)

  await signIn(page, withBilling.host, `${withBilling.code}:${withBilling.adminEmail}`, withBilling.adminPassword)
  expect(await sidebarApps(page)).toContain('Billing')
  await openRemote(page, withBilling.host, 'billing', 'customers')
  await expect(page.getByRole('heading', { name: 'Customers' })).toBeVisible()

  // Keycloak's session cookie is shared by every host on this machine, so a second identity needs its own context.
  const otherContext = await browser.newContext()
  const otherPage = await otherContext.newPage()
  const otherHealth = watchConsole(otherPage)
  await signIn(
    otherPage,
    withoutBilling.host,
    `${withoutBilling.code}:${withoutBilling.adminEmail}`,
    withoutBilling.adminPassword,
  )
  expect(await sidebarApps(otherPage)).not.toContain('Billing')
  await otherHealth.expectClean()
  await otherContext.close()

  const admin = await platformAdminToken()
  await setTenantApp(admin, withBilling.tenantId, 'billing', false)

  await page.reload()
  await expect.poll(async () => sidebarApps(page), { timeout: 60_000 }).not.toContain('Billing')
  await health.expectClean()
})
