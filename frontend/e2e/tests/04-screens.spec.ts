import { env } from '../support/env'
import { expect, openRemote, signIn, test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { provisionTenantFixture, seedBilling, waitForBilling } from '../support/stack'

const PLATFORM_PAGES: Array<[string, string]> = [
  ['tenants', 'Tenants'],
  ['users', 'Users'],
  ['roles', 'Roles'],
  ['systems', 'Tenant systems'],
  ['remotes', 'UI remotes'],
]

const BILLING_GROUPS: Array<{ group: string; pages: Array<[string, string]> }> = [
  {
    group: 'money core',
    pages: [
      ['customers', 'Customers'],
      ['invoices', 'Invoices'],
      ['charges', 'Charges'],
    ],
  },
  {
    group: 'pricing',
    pages: [
      ['plans', 'Pricing plans'],
      ['pricing', 'Price check'],
    ],
  },
  {
    group: 'wallets and usage',
    pages: [
      ['wallets', 'Wallets'],
      ['usage', 'Usage'],
    ],
  },
  {
    group: 'webhooks and Stripe',
    pages: [
      ['webhooks', 'Webhooks'],
      ['stripe', 'Stripe'],
    ],
  },
]

/**
 * Every screen of both remotes against the real backends: the Platform administration pages in the Platform Shell, and
 * the four Billing groups in a tenant's own shell, with the records the fixture seeded through Billing's API.
 */
test('every Platform and Billing screen works against the backends', async ({ browser, page }) => {
  test.setTimeout(600_000)

  const tenant = await provisionTenantFixture({ billing: true, platform: true, prefix: 'e2e-screen' })
  await waitForBilling(tenant.host, tenant.token)
  const seeded = await seedBilling(tenant)

  await signIn(page, env.platformHost, `platform:${env.platformAdmin.email}`, env.platformAdmin.password)
  for (const [section, heading] of PLATFORM_PAGES) {
    await openRemote(page, env.platformHost, 'platform', section)
    await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible()
    await expect(page.getByText('That did not work')).toHaveCount(0)
  }

  // The tenants screen finds the fixture, and its sheet shows the app entitlements the tenant was given.
  await openRemote(page, env.platformHost, 'platform', 'tenants')
  await page.getByRole('textbox', { name: 'Search by tenant code' }).fill(tenant.code)
  const tenantRow = page.getByRole('row').filter({ hasText: tenant.code })
  await expect(tenantRow).toBeVisible()
  await tenantRow.click()
  const sheet = page.getByRole('dialog')
  await expect(sheet.getByRole('heading', { name: `E2E ${tenant.code}` })).toBeVisible()
  await expect(sheet.getByRole('heading', { name: 'App entitlements' })).toBeVisible()
  await expect(sheet.locator('li').filter({ hasText: 'Billing' })).toContainText('On')
  await page.keyboard.press('Escape')

  const tenantContext = await browser.newContext()
  const tenantPage = await tenantContext.newPage()
  // A tenant that has not connected Stripe answers 404 for its configuration; the screen shows "not connected", which
  // is a state, not a broken request.
  const tenantHealth = watchConsole(tenantPage, {
    allowStatus: (status, url) => status === 404 && url.endsWith('/api/v1/stripe/configuration'),
  })
  await signIn(tenantPage, tenant.host, `${tenant.code}:${tenant.adminEmail}`, tenant.adminPassword)

  for (const group of BILLING_GROUPS) {
    for (const [section, heading] of group.pages) {
      await openRemote(tenantPage, tenant.host, 'billing', section)
      await expect(tenantPage.getByRole('heading', { name: heading, level: 1 })).toBeVisible()
      await expect(tenantPage.getByText('That did not work')).toHaveCount(0)
    }
  }

  // The seeded records are the ones the list screens show, so the pages are reading the tenant's own data.
  await openRemote(tenantPage, tenant.host, 'billing', 'invoices')
  await expect(tenantPage.getByRole('row').filter({ hasText: seeded.invoiceNumber })).toBeVisible()
  await openRemote(tenantPage, tenant.host, 'billing', 'customers')
  await expect(tenantPage.getByRole('row').filter({ hasText: `e2e@${tenant.code}.test` })).toBeVisible()

  await tenantHealth.expectClean()
  await tenantContext.close()
})
