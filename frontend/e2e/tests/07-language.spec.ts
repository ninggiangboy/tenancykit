import { expect, openRemote, signIn, test } from '../support/fixtures'
import { provisionTenantFixture, waitForBilling } from '../support/stack'

/**
 * The console speaks English and Vietnamese. The shell owns the choice, every remote follows it, and the design
 * system's own strings (a selection, a sort) follow it too rather than staying English inside a Vietnamese screen.
 */
test('the shell, both remotes and the design system switch to Vietnamese and keep it', async ({ page }) => {
  test.setTimeout(300_000)

  const tenant = await provisionTenantFixture({ billing: true, platform: true, prefix: 'e2e-lang' })
  await waitForBilling(tenant.host, tenant.token)

  await signIn(page, tenant.host, `${tenant.code}:${tenant.adminEmail}`, tenant.adminPassword)
  await page.getByRole('button', { name: 'Language' }).click()
  await page.getByRole('menuitemradio', { name: 'Vietnamese' }).click()

  await expect(page.locator('aside nav').first()).toHaveAttribute('aria-label', 'Ứng dụng')
  await expect(page.getByRole('button', { name: /Ngôn ngữ/ })).toBeVisible()

  await openRemote(page, tenant.host, 'billing', 'customers')
  await expect(page.getByRole('heading', { name: 'Khách hàng', level: 1 })).toBeVisible()
  // A design-system string, not a remote's: the sort control names itself in Vietnamese.
  await expect(page.getByRole('button', { name: 'Sắp xếp theo Khách hàng' })).toBeVisible()

  await openRemote(page, tenant.host, 'platform', 'users')
  await expect(page.getByRole('heading', { name: 'Người dùng', level: 1 })).toBeVisible()

  await page.reload()
  await expect(page.locator('aside nav').first()).toHaveAttribute('aria-label', 'Ứng dụng')
  await openRemote(page, tenant.host, 'billing', 'customers')
  await expect(page.getByRole('heading', { name: 'Khách hàng', level: 1 })).toBeVisible()
})
