import { env } from '../support/env'
import { expect, openRemote, signIn, test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { seedBillingRich } from '../support/seed-rich'
import { provisionTenantFixture, seedBilling, waitForBilling } from '../support/stack'
import { applyLook, axeLook, capture, DENSITIES, THEMES, VIEWPORTS } from '../support/visual'

const PLATFORM_SCREENS: Array<[string, string]> = [
  ['tenants', 'Tenants'],
  ['users', 'Users'],
  ['roles', 'Roles'],
  ['systems', 'Tenant systems'],
  ['remotes', 'UI remotes'],
]

const BILLING_SCREENS: Array<[string, string]> = [
  ['customers', 'Customers'],
  ['invoices', 'Invoices'],
  ['charges', 'Charges'],
  ['payments', 'Payments'],
  ['refunds', 'Refunds'],
  ['subscriptions', 'Subscriptions'],
  ['plans', 'Pricing plans'],
  ['pricing', 'Price check'],
  ['coupons', 'Coupons'],
  ['redemptions', 'Redemptions'],
  ['wallets', 'Wallets'],
  ['reservations', 'Reservations'],
  ['usage', 'Usage'],
  ['webhooks', 'Webhooks'],
  ['stripe', 'Stripe'],
]

/** A tenant that has not connected Stripe answers 404 for its configuration; the screen shows "not connected". */
const allowStripe404 = (status: number, url: string): boolean =>
  status === 404 && url.endsWith('/api/v1/stripe/configuration')

/**
 * Every Platform and Billing screen, and each shell's home, in the twelve looks: three widths, two themes and two
 * densities. A capture also proves the phone screens do not scroll sideways and no screen shows the error state.
 */
test('every screen holds in every look', async ({ browser }) => {
  test.setTimeout(1_800_000)

  const tenant = await provisionTenantFixture({ billing: true, platform: true, prefix: 'e2e-visual' })
  await waitForBilling(tenant.host, tenant.token)
  await seedBilling(tenant)
  await seedBillingRich(tenant)

  const platformContext = await browser.newContext()
  const platformPage = await platformContext.newPage()
  watchConsole(platformPage)
  await signIn(platformPage, env.platformHost, env.platformAdmin.email, env.platformAdmin.password)

  const tenantContext = await browser.newContext()
  const tenantPage = await tenantContext.newPage()
  watchConsole(tenantPage, { allowStatus: allowStripe404 })
  await signIn(tenantPage, tenant.host, tenant.adminEmail, tenant.adminPassword)

  for (const [viewportName, viewport] of Object.entries(VIEWPORTS)) {
    await platformPage.setViewportSize(viewport)
    await tenantPage.setViewportSize(viewport)
    for (const theme of THEMES) {
      for (const density of DENSITIES) {
        const look = `${viewportName}-${theme}-${density}`
        await applyLook(platformPage, theme, density)
        await applyLook(tenantPage, theme, density)

        await platformPage.goto(env.consoleUrl(env.platformHost))
        await expect(platformPage.locator('h1').first()).toBeVisible()
        expect(await platformPage.evaluate(() => document.documentElement.dataset.app)).toBeUndefined()
        await capture(platformPage, `platform-home-${look}`)

        await tenantPage.goto(env.consoleUrl(tenant.host))
        await expect(tenantPage.locator('h1').first()).toBeVisible()
        expect(await tenantPage.evaluate(() => document.documentElement.dataset.app)).toBeUndefined()
        await capture(tenantPage, `tenant-home-${look}`)

        for (const [section, heading] of PLATFORM_SCREENS) {
          await openRemote(platformPage, env.platformHost, 'platform', section)
          await expect(platformPage.getByRole('heading', { name: heading, level: 1 })).toBeVisible()
          expect(await platformPage.evaluate(() => document.documentElement.dataset.app)).toBe('platform')
          await capture(platformPage, `platform-${section}-${look}`)
        }

        for (const [section, heading] of BILLING_SCREENS) {
          await openRemote(tenantPage, tenant.host, 'billing', section)
          await expect(tenantPage.getByRole('heading', { name: heading, level: 1 })).toBeVisible()
          expect(await tenantPage.evaluate(() => document.documentElement.dataset.app)).toBe('billing')
          await capture(tenantPage, `tenant-${section}-${look}`)
        }
      }
    }
  }

  // Contrast and touch targets, at the two widths where they can fail, in both themes.
  for (const viewport of [VIEWPORTS.desktop, VIEWPORTS.phone]) {
    await tenantPage.setViewportSize(viewport)
    for (const theme of THEMES) {
      await applyLook(tenantPage, theme, 'comfortable')
      await openRemote(tenantPage, tenant.host, 'billing', 'invoices')
      expect(await axeLook(tenantPage), `invoices ${theme} at ${viewport.width}`).toEqual([])
    }
  }

  await platformPage.setViewportSize(VIEWPORTS.phone)
  await applyLook(platformPage, 'light', 'comfortable')
  await openRemote(platformPage, env.platformHost, 'platform', 'tenants')
  expect(await axeLook(platformPage), 'tenants light at 390').toEqual([])

  // The invoice list's own rules: row height follows density, no selection without bulk actions, and the primary
  // action takes the app's accent.
  await tenantPage.setViewportSize(VIEWPORTS.desktop)
  await applyLook(tenantPage, 'light', 'comfortable')
  await openRemote(tenantPage, tenant.host, 'billing', 'invoices')
  const comfortable = await tenantPage.locator('tbody tr').first().boundingBox()
  expect(Math.round(comfortable?.height ?? 0), 'a row in comfortable density').toBe(52)

  await applyLook(tenantPage, 'light', 'compact')
  const compact = await tenantPage.locator('tbody tr').first().boundingBox()
  expect(Math.round(compact?.height ?? 0), 'a row in compact density').toBe(40)

  await expect(tenantPage.locator('table input[type="checkbox"]')).toHaveCount(0)

  await applyLook(tenantPage, 'light', 'comfortable')
  const create = tenantPage.getByRole('button', { name: 'Create invoice' }).first()
  const accent = await create.evaluate((element) => getComputedStyle(element).backgroundColor)
  const expected = await tenantPage.evaluate(() => {
    const probe = document.createElement('span')
    probe.style.color = 'var(--app-billing)'
    document.body.append(probe)
    const color = getComputedStyle(probe).color
    probe.remove()
    return color
  })
  expect(accent, 'the create button takes the app accent').toBe(expected)

  await tenantContext.close()
  await platformContext.close()
})
