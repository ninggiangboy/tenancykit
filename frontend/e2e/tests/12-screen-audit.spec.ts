import type { Locator, Page } from '@playwright/test'
import {
  AUDIT_DIR,
  type Finding,
  type Measure,
  type MeasureOptions,
  measure,
  probeOverlay,
  settle,
  shot,
  writeReport,
} from '../support/audit'
import { env } from '../support/env'
import { rawExpect as expect, openRemote, signIn, rawTest as test } from '../support/fixtures'
import { watchConsole } from '../support/health'
import { seedBillingRich } from '../support/seed-rich'
import { provisionTenantFixture, seedBilling, type TenantFixture, waitForBilling } from '../support/stack'
import { applyLook, VIEWPORTS } from '../support/visual'

/**
 * The screen audit: every Platform and Billing screen, each shell's home, and what each one opens (the record sheet and
 * its actions, the page's actions, the filter popovers, the tabs and the row menu), measured against the acceptance
 * criteria and captured. It collects findings instead of stopping at the first, writes `report.md` and `findings.json`
 * under `test-results/audit/` (or `AUDIT_DIR`), and fails when any finding other than the harness's own notes is left. Set
 * `AUDIT_STRICT=0` to collect the report without failing, for example while reviewing a new screen.
 *
 * What the audit can reach depends on the data the fixture seeded: a list with no rows has no sheet to open. The
 * fixture seeds every Billing list except payments and refunds (see `support/seed-rich.ts`).
 */

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

/** Screens that load a list on arrival, which is what the loading and error states are checked on (a screen that first asks for a tenant or a wallet has nothing to load). */
const LISTS: Array<{ app: 'platform' | 'billing'; section: string }> = [
  ...['tenants', 'users', 'roles'].map((section) => ({ app: 'platform' as const, section })),
  ...[
    'customers',
    'invoices',
    'charges',
    'payments',
    'refunds',
    'subscriptions',
    'plans',
    'coupons',
    'wallets',
    'reservations',
    'usage',
    'webhooks',
  ].map((section) => ({ app: 'billing' as const, section })),
]

interface Look {
  name: string
  viewport: { width: number; height: number }
  theme: 'light' | 'dark'
  density: 'comfortable' | 'compact'
  /** A deep look also opens sheets, dialogs, popovers and tabs; the others measure and capture each screen. */
  deep: boolean
}

const LOOKS: Look[] = [
  {
    name: 'desktop-light-comfortable',
    viewport: VIEWPORTS.desktop,
    theme: 'light',
    density: 'comfortable',
    deep: true,
  },
  { name: 'desktop-dark-compact', viewport: VIEWPORTS.desktop, theme: 'dark', density: 'compact', deep: false },
  { name: 'tablet-light-comfortable', viewport: VIEWPORTS.tablet, theme: 'light', density: 'comfortable', deep: false },
  { name: 'phone-light-comfortable', viewport: VIEWPORTS.phone, theme: 'light', density: 'comfortable', deep: true },
  { name: 'phone-dark-compact', viewport: VIEWPORTS.phone, theme: 'dark', density: 'compact', deep: false },
]

const allowStripe404 = (status: number, url: string): boolean =>
  status === 404 && url.endsWith('/api/v1/stripe/configuration')

const findings: Finding[] = []
const seen = new Set<string>()

function add(look: string, screen: string, rule: string, target: string, detail: string): void {
  const key = [look, screen, rule, target].join('|')
  if (seen.has(key)) return
  seen.add(key)
  findings.push({ look, screen, rule, target, detail })
}

function addAll(look: string, screen: string, items: Measure[]): void {
  for (const item of items) add(look, screen, item.rule, item.target, item.detail)
}

const slug = (text: string): string =>
  text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 28)

const ANY_MODAL = '[role=dialog], [role=alertdialog]'

/** Closes whatever is open with Escape, and reloads the screen if something refuses to close. */
async function recover(page: Page, reopen: () => Promise<void>): Promise<void> {
  for (let attempt = 0; attempt < 3; attempt++) {
    if ((await page.locator(`${ANY_MODAL}, [role=menu]`).count()) === 0) return
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  }
  if ((await page.locator(`${ANY_MODAL}, [role=menu]`).count()) > 0) {
    await reopen()
    await settle(page)
  }
}

/**
 * A modal that has just opened: its motion, size, placement, handle, focus and close button; then its own controls and a
 * capture; then its exit and where focus goes.
 */
async function auditOverlay(
  page: Page,
  look: Look,
  screen: string,
  name: string,
  options: MeasureOptions,
  opener?: Locator,
): Promise<void> {
  // Motion is read at once, while the enter is playing; the geometry below once it has settled.
  const early = await probeOverlay(page)
  if (!early) return
  const target = name
  if (early.entering === 0)
    add(look.name, screen, 'motion: the modal has no enter animation', target, 'no animation or transition is running')
  await page.waitForTimeout(450)
  const probe = (await probeOverlay(page)) ?? early
  if (!probe.focusInside)
    add(look.name, screen, 'D1 focus does not move into the modal', target, 'activeElement is outside it')

  const { box, viewport } = probe
  const fromRight = Math.abs(box.x + box.width - viewport.width) < 12 && box.height > viewport.height - 24
  if (options.phone) {
    if (Math.abs(box.y + box.height - viewport.height) > 1) {
      add(
        look.name,
        screen,
        'M5 the modal is not docked to the bottom',
        target,
        `bottom at ${Math.round(box.y + box.height)} of ${viewport.height}`,
      )
    }
    if (box.height > viewport.height * 0.89) {
      add(
        look.name,
        screen,
        'M5 the modal is taller than 88%',
        target,
        `${Math.round((box.height / viewport.height) * 100)}%`,
      )
    }
    if (!probe.handle) add(look.name, screen, 'M5 the modal has no grab handle', target, 'no .touch-none handle')
    if (probe.closeBox && (probe.closeBox.height < 43.5 || probe.closeBox.width < 43.5)) {
      add(
        look.name,
        screen,
        'M2 the close button is below 44px',
        target,
        `${Math.round(probe.closeBox.height)}x${Math.round(probe.closeBox.width)}`,
      )
    }
  } else if (fromRight && Math.abs(box.width - 460) > 2) {
    add(look.name, screen, 'D2 the side sheet is not 460px wide', target, `${Math.round(box.width)}px`)
  } else if (!fromRight && box.width > 642) {
    add(look.name, screen, 'D4 the dialog is wider than 640px', target, `${Math.round(box.width)}px`)
  }

  addAll(look.name, screen, await measure(page, { ...options, scope: '[data-audit-panel]' }))
  await shot(page, `${look.name}--${screen}--${name}`)

  await page.keyboard.press('Escape')
  await page.waitForTimeout(40)
  if ((await page.locator('[data-audit-panel]').count()) === 0) {
    add(look.name, screen, 'motion: the modal has no exit animation', target, 'it was gone 40ms after Escape')
  }
  await page.waitForTimeout(450)
  if (opener && (await page.locator(ANY_MODAL).count()) === 0) {
    const returned = await page.evaluate(() =>
      Boolean(document.activeElement && document.activeElement !== document.body),
    )
    if (!returned) add(look.name, screen, 'D1 focus is not returned to the opener', target, 'activeElement is the body')
  }
}

async function buttonNames(scope: Locator, skip: RegExp): Promise<string[]> {
  const names = await scope
    .locator('button')
    .evaluateAll((buttons) =>
      buttons.map((button) =>
        (button.getAttribute('aria-label') ?? button.textContent ?? '').replace(/\s+/g, ' ').trim(),
      ),
    )
  return [...new Set(names)].filter((name) => name && !skip.test(name)).slice(0, 6)
}

/** The record sheet of the first row, and each of its buttons. */
async function exerciseSheet(
  page: Page,
  look: Look,
  screen: string,
  options: MeasureOptions,
  reopen: () => Promise<void>,
) {
  const row = options.phone
    ? page.locator('ul[aria-label] > li[tabindex="0"]').first()
    : page.locator('tbody tr').first()
  if ((await row.count()) === 0) return
  const open = async () => {
    // The first cell: a later one may hold a copy button, which keeps its own click.
    await (options.phone ? row : row.locator('td').first()).click()
    await page.getByRole('dialog').first().waitFor({ timeout: 8_000 })
  }

  try {
    await open()
    const names = await buttonNames(page.getByRole('dialog').first(), /^(close|previous|next|clear)$|copy/i)
    // The sheet itself: the first look is the motion and size, then its buttons are tried one by one.
    const probe = await probeOverlay(page)
    if (probe && probe.entering === 0) {
      add(
        look.name,
        screen,
        'motion: the modal has no enter animation',
        'sheet',
        'no animation or transition is running',
      )
    }
    await page.waitForTimeout(450)
    addAll(look.name, screen, await measure(page, { ...options, scope: '[data-audit-panel]' }))
    await shot(page, `${look.name}--${screen}--sheet`)

    for (const name of names) {
      if ((await page.locator(ANY_MODAL).count()) === 0) await open()
      const button = page.getByRole('dialog').first().getByRole('button', { name, exact: true }).first()
      if ((await button.count()) === 0) continue
      await button.click({ timeout: 4_000 }).catch(() => undefined)
      await page.waitForTimeout(80)
      const modals = await page.locator(ANY_MODAL).count()
      if (modals >= 2) {
        await auditOverlay(page, look, screen, `sheet-action-${slug(name)}`, options)
      } else if ((await page.locator('[role=menu]').count()) > 0) {
        addAll(look.name, screen, await measure(page, { ...options, scope: '[role=menu]' }))
        await shot(page, `${look.name}--${screen}--sheet-menu-${slug(name)}`)
        await page.keyboard.press('Escape')
      }
      await recover(page, reopen)
    }
    await recover(page, reopen)
  } catch (error) {
    add(look.name, screen, 'harness: the sheet could not be exercised', 'sheet', String(error).slice(0, 120))
    await recover(page, reopen)
  }
}

/** The page's own actions: each button in the page head, and the floating button on a phone. */
async function exercisePageActions(
  page: Page,
  look: Look,
  screen: string,
  options: MeasureOptions,
  reopen: () => Promise<void>,
) {
  const head = page.locator('h1').first().locator('xpath=ancestor::header[1]')
  const fab = page.locator('div.fixed.right-4.animate-rise-in')
  const skip = /export|download|sign out|open navigation|search/i
  const names = [...new Set([...(await buttonNames(head, skip)), ...(await buttonNames(fab, skip))])]
  for (const name of names) {
    try {
      const button = page.getByRole('button', { name, exact: true }).last()
      await button.click({ timeout: 4_000 })
      await page.waitForTimeout(80)
      if ((await page.locator(ANY_MODAL).count()) > 0) {
        await auditOverlay(page, look, screen, `action-${slug(name)}`, options, button)
      } else if ((await page.locator('[role=menu]').count()) > 0) {
        await shot(page, `${look.name}--${screen}--menu-${slug(name)}`)
      }
    } catch (error) {
      add(look.name, screen, 'harness: a page action could not be exercised', name, String(error).slice(0, 120))
    }
    await recover(page, reopen)
  }
}

/** Filter popovers and menus in the page (and, on the home, in the top bar), and the row menu. */
async function exercisePopups(
  page: Page,
  look: Look,
  screen: string,
  options: MeasureOptions,
  reopen: () => Promise<void>,
  shell: boolean,
) {
  const count = await page.evaluate((inShell) => {
    let index = 0
    for (const element of document.querySelectorAll('[aria-haspopup]')) {
      const rect = element.getBoundingClientRect()
      if (rect.width < 2 || element.closest('aside, [role=dialog]')) continue
      if (rect.top < 56 !== inShell) continue
      element.setAttribute('data-audit-popup', String(index++))
    }
    return index
  }, shell)

  for (let index = 0; index < Math.min(count, 6); index++) {
    const trigger = page.locator(`[data-audit-popup="${index}"]`)
    try {
      await trigger.click({ timeout: 4_000 })
      await page.locator('[data-radix-popper-content-wrapper]').first().waitFor({ timeout: 3_000 })
      const running = await page.evaluate(
        () =>
          document.querySelector('[data-radix-popper-content-wrapper]')?.firstElementChild?.getAnimations().length ?? 0,
      )
      if (running === 0)
        add(
          look.name,
          screen,
          'motion: the popover has no enter animation',
          `popup ${index}`,
          'no animation or transition is running',
        )
      await page.waitForTimeout(300)
      addAll(look.name, screen, await measure(page, { ...options, scope: '[data-radix-popper-content-wrapper]' }))
      await shot(page, `${look.name}--${screen}--popup-${index}`)
    } catch (error) {
      add(look.name, screen, 'harness: a popup could not be exercised', `popup ${index}`, String(error).slice(0, 120))
    }
    await recover(page, reopen)
  }

  if (shell || options.phone) return
  const more = page.locator('tbody tr').first().locator('td:last-child button[aria-haspopup]').first()
  if ((await more.count()) === 0) return
  try {
    await page.locator('tbody tr').first().hover()
    await more.click({ timeout: 4_000 })
    await page.waitForTimeout(300)
    await shot(page, `${look.name}--${screen}--row-menu`)
  } catch {
    // a row without a menu is not a finding
  }
  await recover(page, reopen)
}

/** Every tab beyond the first: a segmented tab is another form or table, a tile is another view of the list. */
async function exerciseTabs(page: Page, look: Look, screen: string, options: MeasureOptions) {
  const tabs = page.getByRole('tab')
  const total = Math.min(await tabs.count(), 6)
  for (let index = 1; index < total; index++) {
    try {
      await tabs.nth(index).click({ timeout: 4_000 })
      await settle(page)
      addAll(look.name, screen, await measure(page, options))
      await shot(page, `${look.name}--${screen}--tab-${index}`)
    } catch (error) {
      add(look.name, screen, 'harness: a tab could not be exercised', `tab ${index}`, String(error).slice(0, 120))
    }
  }
}

test.describe
  .serial('screen audit', () => {
    let tenant: TenantFixture

    test.beforeAll(async () => {
      test.setTimeout(600_000)
      tenant = await provisionTenantFixture({ billing: true, platform: true, prefix: 'e2e-audit' })
      await waitForBilling(tenant.host, tenant.token)
      await seedBilling(tenant)
      // Enough in every list to open a sheet on each screen; payments and refunds need Stripe and stay empty.
      const rich = await seedBillingRich(tenant)
      console.log(`audit seed: ${JSON.stringify({ ...rich, notes: undefined })}`)
      for (const note of rich.notes) console.log(`audit seed note: ${note}`)
    })

    for (const look of LOOKS) {
      test(`every screen, ${look.name}`, async ({ browser }) => {
        test.setTimeout(2_400_000)
        const options: MeasureOptions = { phone: look.viewport.width < 768, compact: look.density === 'compact' }
        const touch = look.viewport.width < 768

        const platformContext = await browser.newContext({ viewport: look.viewport, hasTouch: touch })
        const platformPage = await platformContext.newPage()
        const platformHealth = watchConsole(platformPage)
        await signIn(platformPage, env.platformHost, `platform:${env.platformAdmin.email}`, env.platformAdmin.password)

        const tenantContext = await browser.newContext({ viewport: look.viewport, hasTouch: touch })
        const tenantPage = await tenantContext.newPage()
        const tenantHealth = watchConsole(tenantPage, { allowStatus: allowStripe404 })
        await signIn(tenantPage, tenant.host, `${tenant.code}:${tenant.adminEmail}`, tenant.adminPassword)

        const runs: Array<{
          page: Page
          health: ReturnType<typeof watchConsole>
          screens: Array<{ id: string; heading: string; open: () => Promise<void>; shell: boolean }>
        }> = [
          {
            page: platformPage,
            health: platformHealth,
            screens: [
              {
                id: 'platform-home',
                heading: '',
                open: () => platformPage.goto(env.consoleUrl(env.platformHost)).then(() => undefined),
                shell: true,
              },
              ...PLATFORM_SCREENS.map(([section, heading]) => ({
                id: `platform-${section}`,
                heading,
                open: () => openRemote(platformPage, env.platformHost, 'platform', section),
                shell: false,
              })),
            ],
          },
          {
            page: tenantPage,
            health: tenantHealth,
            screens: [
              {
                id: 'tenant-home',
                heading: '',
                open: () => tenantPage.goto(env.consoleUrl(tenant.host)).then(() => undefined),
                shell: true,
              },
              ...BILLING_SCREENS.map(([section, heading]) => ({
                id: `tenant-${section}`,
                heading,
                open: () => openRemote(tenantPage, tenant.host, 'billing', section),
                shell: false,
              })),
            ],
          },
        ]

        for (const run of runs) {
          await applyLook(run.page, look.theme, look.density)
          let drained = 0
          for (const screen of run.screens) {
            try {
              await screen.open()
              await settle(run.page)
              addAll(look.name, screen.id, await measure(run.page, options))
              await shot(run.page, `${look.name}--${screen.id}`)
              if (look.deep) {
                await exerciseTabs(run.page, look, screen.id, options)
                // A tab leaves its view in the URL, so the next steps start from the screen as a person arrives.
                await screen.open()
                await settle(run.page)
                await exercisePopups(run.page, look, screen.id, options, screen.open, screen.shell)
                if (!screen.shell) {
                  await exerciseSheet(run.page, look, screen.id, options, screen.open)
                  // A sheet button can change the record; the page's own actions start from the screen as it loads.
                  await screen.open()
                  await settle(run.page)
                  await exercisePageActions(run.page, look, screen.id, options, screen.open)
                }
              }
            } catch (error) {
              add(
                look.name,
                screen.id,
                'harness: the screen could not be audited',
                screen.id,
                String(error).slice(0, 160),
              )
            }
            for (const problem of run.health.problems.slice(drained)) {
              add(look.name, screen.id, 'E5 console error or failed request', 'console', problem.slice(0, 160))
            }
            drained = run.health.problems.length
          }
        }

        // The phone's drawer: out of the tab order while closed, and its own controls are touch targets once open.
        if (options.phone) {
          await openRemote(tenantPage, tenant.host, 'billing', 'invoices')
          await settle(tenantPage)
          const inert = await tenantPage.evaluate(() => document.querySelector('aside')?.hasAttribute('inert') ?? false)
          if (!inert)
            add(look.name, 'tenant-drawer', 'M3 the closed drawer is still in the tab order', 'aside', 'not inert')
          await tenantPage.getByRole('button', { name: 'Open navigation' }).click()
          await tenantPage.waitForTimeout(450)
          addAll(look.name, 'tenant-drawer', await measure(tenantPage, { ...options, scope: 'aside' }))
          await shot(tenantPage, `${look.name}--tenant-drawer`)
          await tenantPage.keyboard.press('Escape')
          await tenantPage.waitForTimeout(450)
        }

        // The tablet's rail is 56px, and its toggle must do something or not be there.
        if (look.name.startsWith('tablet')) {
          await openRemote(tenantPage, tenant.host, 'billing', 'invoices')
          const aside = tenantPage.locator('aside').first()
          const width = Math.round((await aside.boundingBox())?.width ?? 0)
          if (Math.abs(width - 56) > 1)
            add(look.name, 'tenant-invoices', 'M9 the rail is not 56px', 'aside', `${width}px`)
          // Rail items name themselves in a tooltip.
          await aside.locator('nav a').nth(1).hover()
          const tip = await tenantPage
            .locator('[role=tooltip]')
            .first()
            .waitFor({ timeout: 2_000 })
            .then(() => true)
            .catch(() => false)
          if (!tip)
            add(look.name, 'tenant-invoices', 'M9 a rail item has no tooltip', 'aside nav a', 'nothing on hover')
          const toggle = aside.getByRole('button', { name: /sidebar/i })
          if ((await toggle.count()) > 0) {
            await toggle.first().click()
            await tenantPage.waitForTimeout(400)
            const after = Math.round((await aside.boundingBox())?.width ?? 0)
            if (after === width)
              add(
                look.name,
                'tenant-invoices',
                'the sidebar toggle does nothing on a tablet',
                'aside toggle',
                `still ${after}px`,
              )
          }
        }

        await tenantContext.close()
        await platformContext.close()
      })
    }

    test('loading and error states of every list', async ({ browser }) => {
      test.setTimeout(1_200_000)
      const look = LOOKS[0] as Look
      const context = await browser.newContext({ viewport: look.viewport })
      const platform = await context.newPage()
      await signIn(platform, env.platformHost, `platform:${env.platformAdmin.email}`, env.platformAdmin.password)
      const tenantContext = await browser.newContext({ viewport: look.viewport })
      const page = await tenantContext.newPage()
      await signIn(page, tenant.host, `${tenant.code}:${tenant.adminEmail}`, tenant.adminPassword)

      const isData = (url: URL, method: string): boolean =>
        method === 'GET' && url.pathname.includes('/api/v1/') && !/\/(console|me|permissions)(\/|$)/.test(url.pathname)

      for (const { app, section } of LISTS) {
        const target = app === 'platform' ? platform : page
        const host = app === 'platform' ? env.platformHost : tenant.host
        const screen = `${app === 'platform' ? 'platform' : 'tenant'}-${section}`
        const open = () => openRemote(target, host, app, section)

        // Loading keeps the layout and shows skeleton rows (E1).
        await target.route(
          (url) => isData(url, 'GET'),
          async (route) => {
            await new Promise((resolve) => setTimeout(resolve, 2_500))
            await route.continue().catch(() => undefined)
          },
        )
        try {
          await open()
          await target.waitForTimeout(700)
          if ((await target.locator('.animate-shimmer').count()) === 0) {
            add(
              'states',
              screen,
              'E1 no skeleton while the list loads',
              'list',
              'no skeleton 700ms after the request started',
            )
          }
          await shot(target, `states--${screen}--loading`)
        } catch (error) {
          add('states', screen, 'harness: loading state', screen, String(error).slice(0, 120))
        }
        await target.unrouteAll({ behavior: 'ignoreErrors' })

        // A failed read offers Retry and never says nothing changed (C7).
        await target.route(
          (url) => isData(url, 'GET'),
          (route) =>
            route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ title: 'Boom' }) }),
        )
        try {
          await open()
          await target.waitForTimeout(1_500)
          if ((await target.getByRole('button', { name: /retry|try again/i }).count()) === 0) {
            add('states', screen, 'C7 a failed read offers no Retry', 'error state', 'no retry button')
          }
          if ((await target.getByText(/nothing changed/i).count()) > 0) {
            add('states', screen, 'C7 a failed read says nothing changed', 'error state', 'the copy of a failed write')
          }
          await shot(target, `states--${screen}--error`)
        } catch (error) {
          add('states', screen, 'harness: error state', screen, String(error).slice(0, 120))
        }
        await target.unrouteAll({ behavior: 'ignoreErrors' })
      }
      await tenantContext.close()
      await context.close()
    })

    test('report', () => {
      const path = writeReport(findings)
      console.log(`screen audit: ${findings.length} findings, report at ${path}, captures in ${AUDIT_DIR}`)
      if (process.env.AUDIT_STRICT !== '0') {
        expect(
          findings.filter((finding) => !finding.rule.startsWith('harness')),
          `findings, see ${path}`,
        ).toEqual([])
      }
    })
  })
