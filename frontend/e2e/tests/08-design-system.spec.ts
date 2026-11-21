import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import AxeBuilder from '@axe-core/playwright'
import { repoRoot } from '../support/env'
import { env, expect, openRemote, signIn, test } from '../support/fixtures'
import { provisionTenantFixture, seedBilling, waitForBilling } from '../support/stack'
import { motionIdle } from '../support/visual'

const tokens = JSON.parse(
  readFileSync(resolve(repoRoot, 'plans/261117-1456-suite-console/reports/design-system/tokens.json'), 'utf8'),
) as { color: { tokens: Array<{ name: string; value: string | { light: string; dark: string } }> } }

/** `{name}` means "that token's value in the same theme", as in the token build. */
const tokenValue = (name: string, theme: 'light' | 'dark'): string => {
  const token = tokens.color.tokens.find((entry) => entry.name === name)
  if (!token) throw new Error(`tokens.json has no ${name}`)
  if (typeof token.value !== 'string') return token.value[theme]
  const target = token.value.match(/^\{(.+)\}$/)?.[1]
  return target ? tokenValue(target, theme) : token.value
}

/** A colour as the browser resolved it; a computed custom property may keep oklch's percentage form. */
const resolvedColor = (page: import('@playwright/test').Page, value: string): Promise<string> =>
  page.evaluate((css) => {
    const probe = document.createElement('span')
    probe.style.color = css
    document.body.append(probe)
    const color = getComputedStyle(probe).color
    probe.remove()
    return color
  }, value)

const tokenMatches = async (
  page: import('@playwright/test').Page,
  name: string,
  theme: 'light' | 'dark',
): Promise<[string, string]> => [
  await resolvedColor(page, `var(--${name})`),
  await resolvedColor(page, tokenValue(name, theme)),
]

/** Inside Billing, `--primary` resolves to the app's accent, which is what the primary action must paint. */
const appAccentMatches = async (
  page: import('@playwright/test').Page,
  theme: 'light' | 'dark',
): Promise<[string, string]> => [
  await resolvedColor(page, 'var(--primary)'),
  await resolvedColor(page, tokenValue('app-billing', theme)),
]

const serious = async (page: import('@playwright/test').Page): Promise<string[]> => {
  await motionIdle(page)
  const results = await new AxeBuilder({ page }).analyze()
  return results.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => `${violation.impact}: ${violation.id} ${violation.help}`)
}

/**
 * The design system's rules, as a person with a keyboard meets them: the palette, the search key, j/k and Enter, the
 * selection, the URL that holds the list's state, the record sheet that survives a reload, a remote dialog above the
 * shell's sheet, the tokens the design system defines, and no serious accessibility violation in either theme.
 */
test('the keyboard, URL state, tokens and accessibility rules hold on the invoice list', async ({ page }) => {
  test.setTimeout(300_000)

  const tenant = await provisionTenantFixture({ billing: true, prefix: 'e2e-ds' })
  await waitForBilling(tenant.host, tenant.token)
  const seeded = await seedBilling(tenant)

  await signIn(page, tenant.host, `${tenant.code}:${tenant.adminEmail}`, tenant.adminPassword)
  await openRemote(page, tenant.host, 'billing', 'invoices')
  await expect(page.getByRole('heading', { name: 'Invoices', level: 1 })).toBeVisible()

  // Cmd or Ctrl K, then Esc closes the top layer.
  await page.keyboard.press('ControlOrMeta+k')
  const palette = page.getByRole('dialog')
  await expect(palette).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(palette).toBeHidden()

  // `/` focuses the list search, and what is typed lands in the URL.
  await page.keyboard.press('/')
  const search = page.getByRole('textbox', { name: 'Search by invoice number' })
  await expect(search).toBeFocused()
  await search.fill(seeded.invoiceNumber.slice(0, 7))
  await expect(page).toHaveURL(/q=INV/)
  await search.fill('')
  await page.getByRole('heading', { name: 'Invoices', level: 1 }).click()

  // Tabs and sort live in the URL too.
  await page.getByRole('tab', { name: 'Paid' }).click()
  await expect(page).toHaveURL(/view=paid/)
  await page.getByRole('tab', { name: 'All' }).click()
  await page.getByRole('button', { name: /Sort by Amount/i }).click()
  await expect(page).toHaveURL(/sort=(-)?total/)

  // j moves, Enter opens the record beside the list, and the sheet's URL reloads to the same record.
  await expect(page.getByRole('row').filter({ hasText: seeded.invoiceNumber })).toBeVisible()
  await page.keyboard.press('j')
  await page.keyboard.press('Enter')
  const sheet = page.getByRole('dialog')
  await expect(sheet).toBeVisible()
  await expect.poll(async () => page.url()).toContain('open=')
  await expect(sheet.getByText(seeded.invoiceNumber)).toBeVisible()
  const deepLink = page.url()
  await page.reload()
  await expect(page.getByRole('dialog').getByText(seeded.invoiceNumber)).toBeVisible()
  expect(page.url()).toBe(deepLink)

  // A remote dialog stacks above the shell's sheet, and closing it returns focus to its opener.
  await page.getByRole('button', { name: 'Void invoice', exact: true }).click()
  const confirm = page.getByRole('alertdialog')
  await expect(confirm).toBeVisible()
  const box = await confirm.boundingBox()
  if (!box) throw new Error('the confirmation has no box')
  const onTop = await page.evaluate(
    ({ x, y }) => document.elementFromPoint(x, y)?.closest('[role="alertdialog"]') !== null,
    { x: box.x + box.width / 2, y: box.y + box.height / 2 },
  )
  expect(onTop).toBe(true)
  await page.keyboard.press('Escape')
  await expect(confirm).toBeHidden()
  await expect(page.getByRole('button', { name: 'Void invoice', exact: true })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()

  // x selects nothing: a list shows a checkbox column only when it has a real bulk action.
  await expect(page.locator('table input[type="checkbox"]')).toHaveCount(0)

  // The token variables the shell loads equal tokens.json, in the light theme and then in the dark one; primary is the
  // app accent inside Billing, so it is compared with the app-billing token instead.
  for (const name of ['background', 'foreground', 'border']) {
    const [actual, expected] = await tokenMatches(page, name, 'light')
    expect(actual, `${name} in light`).toBe(expected)
  }
  {
    const [actual, expected] = await appAccentMatches(page, 'light')
    expect(actual, 'primary in light').toBe(expected)
  }
  expect(await serious(page)).toEqual([])

  await page.getByRole('button', { name: 'Theme' }).click()
  await page.getByRole('menuitem', { name: 'Dark' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  for (const name of ['background', 'foreground', 'border']) {
    const [actual, expected] = await tokenMatches(page, name, 'dark')
    expect(actual, `${name} in dark`).toBe(expected)
  }
  {
    const [actual, expected] = await appAccentMatches(page, 'dark')
    expect(actual, 'primary in dark').toBe(expected)
  }
  expect(await serious(page)).toEqual([])
})

test('the same rules hold on the role page in the Platform Shell', async ({ page }) => {
  test.setTimeout(300_000)

  const tenant = await provisionTenantFixture({ platform: true, prefix: 'e2e-ds' })
  await signIn(page, env.platformHost, `platform:${env.platformAdmin.email}`, env.platformAdmin.password)
  await openRemote(page, env.platformHost, 'platform', 'roles')
  await expect(page.getByRole('heading', { name: 'Roles', level: 1 })).toBeVisible()

  await page.keyboard.press('ControlOrMeta+k')
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  // The palette leaves with an animation; the search key is a field key until it has gone.
  await expect(page.getByRole('dialog')).toBeHidden()

  await page.keyboard.press('/')
  const search = page.getByRole('textbox', { name: 'Search by role name' })
  await expect(search).toBeFocused()
  await search.fill('Tenant')
  await expect(page).toHaveURL(/q=Tenant/)
  await page.getByRole('heading', { name: 'Roles', level: 1 }).click()

  // A facet is in the URL; no list offers selection without a real bulk action.
  await page.getByRole('button', { name: 'Scope' }).click()
  await page.getByRole('checkbox', { name: 'Tenant' }).click()
  await expect(page).toHaveURL(/f_scope=TENANT/)
  await page.keyboard.press('Escape')
  // The popover closes on Escape and Radix hands focus back; the keys only land once it is gone.
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('row').nth(1)).toBeVisible()
  await expect(page.locator('table input[type="checkbox"]')).toHaveCount(0)

  expect(await serious(page)).toEqual([])
  expect(tenant.code).not.toBe('')
})
