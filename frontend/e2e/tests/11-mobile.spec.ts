import { dragSheetDown } from '../support/audit'
import { expect, openRemote, signIn, test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { provisionTenantFixture, seedBilling, waitForBilling } from '../support/stack'

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true })

/** A tenant that has not connected Stripe answers 404 for its configuration; the screen shows "not connected". */
const allowStripe404 = (status: number, url: string): boolean =>
  status === 404 && url.endsWith('/api/v1/stripe/configuration')

/**
 * The phone layout as a person uses it: the drawer navigates and puts itself away, the list is cards, a record rises
 * from the bottom and can be dragged down, Back closes it, the page's action floats, and density is one tap away.
 */
test('the phone flows work by touch', async ({ page }) => {
  test.setTimeout(300_000)

  const tenant = await provisionTenantFixture({ billing: true, prefix: 'e2e-mobile' })
  await waitForBilling(tenant.host, tenant.token)
  const seeded = await seedBilling(tenant)
  const health = watchConsole(page, { allowStatus: allowStripe404 })

  await signIn(page, tenant.host, tenant.adminEmail, tenant.adminPassword)
  // The drawer closes on a route change, and the shell settles its first route a moment after the frame paints.
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  // The drawer opens over the page, a link navigates, and the drawer puts itself away.
  const drawer = page.locator('aside').first()
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await drawer.getByRole('link', { name: 'Billing' }).click()
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? 0).toBeLessThan(0)

  await page.getByRole('button', { name: 'Open navigation' }).click()
  await drawer.getByRole('link', { name: 'Invoices' }).click()
  await expect(page.getByRole('heading', { name: 'Invoices', level: 1 })).toBeVisible()
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? 0).toBeLessThan(0)

  // The list is cards on a phone, and a card opens the record as a bottom sheet.
  const cards = page.getByRole('list', { name: 'Invoices' }).getByRole('listitem')
  const card = cards.filter({ hasText: seeded.invoiceNumber })
  await expect(card).toBeVisible()
  await card.click()

  const sheet = page.getByRole('dialog')
  await expect(sheet).toBeVisible()
  // The sheet rises into place, so its bottom edge is read once it has arrived.
  await expect
    .poll(async () => {
      const box = await sheet.boundingBox()
      return Math.round((box?.y ?? 0) + (box?.height ?? 0))
    })
    .toBe(844)
  const sheetBox = await sheet.boundingBox()
  expect(sheetBox?.height ?? 0).toBeGreaterThanOrEqual(700)

  // Dragging the handle down closes it.
  await dragSheetDown(page)
  await expect(sheet).toBeHidden()

  // Back closes the record too.
  await card.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect.poll(async () => page.url()).toContain('open=')
  await page.goBack()
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect.poll(async () => page.url()).not.toContain('open=')

  // The sheet's URL is a replaced entry, so Back leaves the list; come back to it for the page's own action.
  await openRemote(page, tenant.host, 'billing', 'invoices')
  await expect(page.getByRole('heading', { name: 'Invoices', level: 1 })).toBeVisible()

  // The page's one action floats above the safe area, and the dialog it opens is a full-width bottom sheet.
  const create = page.getByRole('button', { name: 'Create invoice' }).last()
  await expect(create).toBeVisible()
  const createBox = await create.boundingBox()
  expect(Math.abs(844 - ((createBox?.y ?? 0) + (createBox?.height ?? 0)))).toBeLessThan(40)
  await create.click()

  const form = page.getByRole('dialog')
  await expect(form).toBeVisible()
  await expect
    .poll(async () => {
      const box = await form.boundingBox()
      return Math.round((box?.y ?? 0) + (box?.height ?? 0))
    })
    .toBe(844)
  const submit = form.getByRole('button', { name: 'Create', exact: true })
  const submitBox = await submit.boundingBox()
  expect(submitBox?.width ?? 0).toBeGreaterThanOrEqual(300)
  await page.keyboard.press('Escape')

  // Density is a choice in the account menu, and it lands on `<html>`.
  await page.getByRole('button', { name: 'Account' }).click()
  await page.getByRole('menuitemradio', { name: 'Compact' }).click()
  await expect.poll(() => page.evaluate(() => document.documentElement.dataset.density)).toBe('compact')

  await health.expectClean()
})
