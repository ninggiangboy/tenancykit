import type { BrowserContext, Page } from '@playwright/test'
import {
  type CloseHow,
  closeAndSample,
  describeFaults,
  dragSheetDown,
  type Fault,
  frameStats,
  isOnTop,
  type LeaveSample,
  LIST_SCREENS,
  settle,
} from '../support/audit'
import { env } from '../support/env'
import { rawExpect as expect, openRemote, signIn, rawTest as test } from '../support/fixtures'
import { seedBillingRich } from '../support/seed-rich'
import { provisionTenantFixture, seedBilling, type TenantFixture, waitForBilling } from '../support/stack'

/**
 * The interaction audit. `12-screen-audit` measures what a screen looks like and how an overlay opens; this file proves
 * what a person does with it: what scrolls, which layer is on top, what a closed overlay does, what a write and a
 * failure show. Each rule is one `describe`; a test names the screens it covered and fails when it covered none, so a
 * seed that stopped producing rows cannot turn a check into a pass.
 */

const DESKTOP = { width: 1280, height: 800 }
const PHONE = { width: 390, height: 844 }

const ANY_MODAL = '[role=dialog], [role=alertdialog]'

/** A toast, not the alert a dialog draws for its own failure. */
const TOAST = '[aria-live=polite] [role=status]'

test.describe('interaction audit', () => {
  let tenant: TenantFixture
  let platformContext: BrowserContext
  let tenantContext: BrowserContext
  let platform: Page
  let billing: Page

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000)
    tenant = await provisionTenantFixture({ billing: true, platform: true, prefix: 'e2e-interact' })
    await waitForBilling(tenant.host, tenant.token)
    await seedBilling(tenant)
    await seedBillingRich(tenant)

    platformContext = await browser.newContext({ viewport: DESKTOP })
    platform = await platformContext.newPage()
    await signIn(platform, env.platformHost, env.platformAdmin.email, env.platformAdmin.password)
    tenantContext = await browser.newContext({ viewport: DESKTOP })
    billing = await tenantContext.newPage()
    await signIn(billing, tenant.host, tenant.adminEmail, tenant.adminPassword)
  })

  test.afterAll(async () => {
    await tenantContext?.close()
    await platformContext?.close()
  })

  const pageFor = (app: 'platform' | 'billing'): Page => (app === 'platform' ? platform : billing)
  const hostFor = (app: 'platform' | 'billing'): string => (app === 'platform' ? env.platformHost : tenant.host)

  async function openScreen(app: 'platform' | 'billing', section: string): Promise<Page> {
    const page = pageFor(app)
    await openRemote(page, hostFor(app), app, section)
    await settle(page)
    return page
  }

  /** The platform's tenant sheet for a tenant, found by its code so the list's other rows do not matter. */
  async function openTenantSheet(code: string, id: string): Promise<void> {
    await openRemote(platform, env.platformHost, 'platform', `tenants?q=${code}&open=${id}`)
    await platform.getByRole('dialog').waitFor({ timeout: 15_000 })
    await settle(platform)
    await platform.waitForTimeout(450)
  }

  /** The first record of the list that is on screen, as a person opens it: a row, or a card on a phone. */
  async function openFirstRecord(page: Page, phone: boolean): Promise<void> {
    const row = phone ? page.locator('ul[aria-label] > li[tabindex="0"]').first() : page.locator('tbody tr').first()
    await (phone ? row : row.locator('td').first()).click()
    await page.getByRole('dialog').first().waitFor({ timeout: 8_000 })
    await page.waitForTimeout(450)
  }

  test.describe('the frame and its scrolling', () => {
    test('the frame is the viewport from 768px up and the page scrolls inside it, under sticky column titles', async () => {
      const faults: Fault[] = []
      const covered: string[] = []
      const fitted: string[] = []
      const optedOut: string[] = []

      for (const { app, section } of LIST_SCREENS) {
        const page = pageFor(app)
        // Short enough that a seven-row list still has to scroll.
        await page.setViewportSize({ width: 1280, height: 520 })
        await openScreen(app, section)
        const screen = `${app}/${section}`
        const measured = await page.evaluate(() => {
          const main = document.querySelector('main') as HTMLElement
          const root = document.scrollingElement as HTMLElement
          const th = document.querySelector('main table thead th') as HTMLElement | null
          const rows = document.querySelectorAll('main table tbody tr').length
          const overflows = main.scrollHeight > main.clientHeight + 1
          let titleOffset: number | null = null
          let position = ''
          let tested = false
          if (th && overflows) {
            position = getComputedStyle(th).position
            // Scroll until the table's own top has left the page; a title sticks only while its table is in view.
            const table = th.closest('table') as HTMLElement
            const mainTop = main.getBoundingClientRect().top
            main.scrollTop += table.getBoundingClientRect().top - mainTop + 30
            const stillInView = table.getBoundingClientRect().bottom - mainTop > th.offsetHeight + 12
            const left = main.scrollTop > 0 && table.getBoundingClientRect().top < mainTop
            tested = stillInView && left
            titleOffset = th.getBoundingClientRect().top - mainTop
            main.scrollTop = 0
          }
          return {
            documentFits: root.scrollHeight <= window.innerHeight + 1,
            documentHeight: root.scrollHeight,
            viewport: window.innerHeight,
            hasTable: th !== null,
            rows,
            overflows,
            position,
            titleOffset,
            tested,
          }
        })
        if (!measured.documentFits) {
          faults.push({
            screen,
            detail: `the document scrolls (${measured.documentHeight} > ${measured.viewport}); only main may`,
          })
        }
        if (!measured.hasTable || measured.rows === 0) continue
        if (!measured.overflows) {
          fitted.push(screen)
          continue
        }
        if (measured.position !== 'sticky') {
          optedOut.push(screen)
          continue
        }
        if (!measured.tested) {
          fitted.push(screen)
          continue
        }
        covered.push(screen)
        if (measured.titleOffset === null || measured.titleOffset > 8 || measured.titleOffset < -1) {
          faults.push({
            screen,
            detail: `column titles are ${measured.titleOffset}px from the top of main after scrolling`,
          })
        }
      }

      console.log(`frame: scrolled and checked ${covered.join(', ')}; fitted without scrolling ${fitted.join(', ')}`)
      console.log(`frame: tables without sticky titles (opted out) ${optedOut.join(', ') || 'none'}`)
      expect(describeFaults(faults)).toBe('')
      expect(covered.length, 'no list had enough rows to scroll, so nothing was checked').toBeGreaterThanOrEqual(3)
    })

    test('a table either fits at 768, 900, 1024 and 1280 with sticky titles, or scrolls sideways without them', async () => {
      const faults: Fault[] = []
      const checked = new Set<string>()
      for (const width of [768, 900, 1024, 1280]) {
        for (const { app, section } of LIST_SCREENS) {
          const page = pageFor(app)
          await page.setViewportSize({ width, height: 800 })
          await openScreen(app, section)
          const state = await page.evaluate(() => {
            const th = document.querySelector('main table thead th') as HTMLElement | null
            if (!th) return null
            const wrapper = th.closest('table')?.parentElement as HTMLElement
            const style = getComputedStyle(wrapper)
            return {
              overflowX: style.overflowX,
              wider: wrapper.scrollWidth > wrapper.clientWidth + 1,
              position: getComputedStyle(th).position,
            }
          })
          if (!state) continue
          checked.add(`${app}/${section}`)
          const screen = `${app}/${section} at ${width}`
          if (state.overflowX === 'clip' && state.wider) {
            faults.push({ screen, detail: 'the table is wider than its frame and the frame clips it' })
          }
          if (state.overflowX === 'clip' && state.position !== 'sticky') {
            faults.push({ screen, detail: 'the frame clips sideways but the titles are not sticky' })
          }
        }
      }
      console.log(`tables checked at four widths: ${[...checked].join(', ')}`)
      expect(describeFaults(faults)).toBe('')
      expect(checked.size, 'no table was found on any list screen').toBeGreaterThanOrEqual(5)
    })

    test('the sidebar keeps the account row and the sign-out button in view', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const box = await billing.getByRole('button', { name: 'Sign out' }).boundingBox()
      expect(box, 'the sign-out button is not rendered').not.toBeNull()
      expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(DESKTOP.height)
      // The app links above it are the part that may scroll.
      const nav = await billing.evaluate(() => {
        const aside = document.querySelector('aside') as HTMLElement
        return aside.scrollHeight <= aside.clientHeight + 1
      })
      expect(nav, 'the whole sidebar scrolls instead of only its navigation').toBe(true)
    })
  })

  test.describe('the layer order', () => {
    for (const [name, viewport] of [
      ['desktop', DESKTOP],
      ['phone', PHONE],
    ] as const) {
      test(`a menu opened from a sheet is above the sheet on ${name}`, async () => {
        await platform.setViewportSize(viewport)
        await openTenantSheet(tenant.code, tenant.tenantId)
        await platform.getByRole('dialog').getByRole('button', { name: 'More actions' }).click()
        await platform.getByRole('menu').waitFor()
        await platform.waitForTimeout(300)
        expect(await isOnTop(platform, '[role=menu]'), 'the menu is drawn behind the sheet').toBe(true)
        await platform.keyboard.press('Escape')
      })

      test(`a toast raised while a sheet is open is above the sheet on ${name}`, async () => {
        await billing.setViewportSize(viewport)
        await openScreen('billing', 'customers')
        await openFirstRecord(billing, viewport.width < 768)
        await billing
          .getByRole('dialog')
          .first()
          .getByRole('button', { name: /^Edit$/ })
          .click()
        const dialog = billing.getByRole('dialog').last()
        await dialog.getByRole('button', { name: 'Save', exact: true }).click()
        await billing.getByRole('status').first().waitFor({ timeout: 10_000 })
        await billing.waitForTimeout(300)
        expect(await isOnTop(billing, TOAST), 'the toast is covered by the sheet').toBe(true)
        await billing.keyboard.press('Escape')
        await billing.waitForTimeout(400)
        if ((await billing.locator(ANY_MODAL).count()) > 0) await billing.keyboard.press('Escape')
      })
    }
  })

  test.describe('leaving', () => {
    /** What a close must look like: still there and closing on the first frame, moving within 140ms, gone by 300ms. */
    function leaveFaults(samples: LeaveSample[]): string[] {
      const faults: string[] = []
      const first = samples[0]
      if (!first?.present || first.state !== 'closed') {
        faults.push(`the sheet is ${first?.present ? `in state ${first.state}` : 'gone'} on the first frame`)
      }
      if (!samples.some((sample) => sample.at <= 140 && sample.moving)) {
        faults.push('nothing moved or faded in the first 140ms')
      }
      if (samples.some((sample) => sample.at >= 300 && sample.present)) faults.push('the sheet is still there at 300ms')
      return faults
    }

    const focusReturned = (page: Page): Promise<boolean> =>
      page.evaluate(() => {
        const active = document.activeElement
        return Boolean(active?.closest('tbody tr, ul[aria-label] > li, button, a'))
      })

    test('every record sheet leaves with its animation on Close, Escape and a click on the scrim, and gives focus back', async () => {
      const faults: Fault[] = []
      const covered: string[] = []
      const sheetless: string[] = []
      await platform.setViewportSize(DESKTOP)
      await billing.setViewportSize(DESKTOP)
      for (const { app, section } of LIST_SCREENS) {
        const page = pageFor(app)
        await openScreen(app, section)
        if ((await page.locator('tbody tr').count()) === 0) continue
        const screen = `${app}/${section}`
        let opened = false
        for (const how of ['button', 'escape', 'scrim'] as CloseHow[]) {
          await page.locator('tbody tr').first().locator('td').first().click()
          const dialog = await page
            .getByRole('dialog')
            .first()
            .waitFor({ timeout: 4_000 })
            .then(() => true)
            .catch(() => false)
          if (!dialog) break
          opened = true
          await page.waitForTimeout(450)
          const problems = leaveFaults(await closeAndSample(page, how))
          for (const problem of problems) faults.push({ screen: `${screen} by ${how}`, detail: problem })
          await page.waitForTimeout(150)
          if (!(await focusReturned(page))) {
            faults.push({ screen: `${screen} by ${how}`, detail: 'focus did not return to the row' })
          }
        }
        if (opened) covered.push(screen)
        else sheetless.push(screen)
      }
      console.log(`leaving: sheets closed three ways on ${covered.join(', ')}; no sheet on ${sheetless.join(', ')}`)
      expect(describeFaults(faults)).toBe('')
      expect(covered.length, 'too few screens had a sheet to close').toBeGreaterThanOrEqual(8)
    })

    test('every record sheet leaves by Close and by a drag down on a phone, and gives focus back', async () => {
      const faults: Fault[] = []
      const covered: string[] = []
      await platform.setViewportSize(PHONE)
      await billing.setViewportSize(PHONE)
      for (const { app, section } of LIST_SCREENS) {
        const page = pageFor(app)
        await openScreen(app, section)
        const card = page.locator('ul[aria-label] > li[tabindex="0"]').first()
        if ((await card.count()) === 0) continue
        const screen = `${app}/${section}`
        await card.click()
        if (
          !(await page
            .getByRole('dialog')
            .first()
            .waitFor({ timeout: 4_000 })
            .then(() => true)
            .catch(() => false))
        ) {
          continue
        }
        await page.waitForTimeout(450)
        for (const problem of leaveFaults(await closeAndSample(page, 'button'))) {
          faults.push({ screen: `${screen} by Close`, detail: problem })
        }
        await page.waitForTimeout(150)
        if (!(await focusReturned(page))) faults.push({ screen: `${screen} by Close`, detail: 'focus did not return' })

        await card.click()
        await page.getByRole('dialog').first().waitFor({ timeout: 4_000 })
        await page.waitForTimeout(450)
        await dragSheetDown(page)
        const gone = await page
          .getByRole('dialog')
          .first()
          .waitFor({ state: 'detached', timeout: 2_000 })
          .then(() => true)
          .catch(() => false)
        if (!gone) faults.push({ screen: `${screen} by drag`, detail: 'the sheet is still there 2s after the drag' })
        await page.waitForTimeout(150)
        if (!(await focusReturned(page))) faults.push({ screen: `${screen} by drag`, detail: 'focus did not return' })
        covered.push(screen)
      }
      console.log(`leaving on a phone: ${covered.join(', ')}`)
      expect(describeFaults(faults)).toBe('')
      expect(covered.length, 'too few screens had a card to open').toBeGreaterThanOrEqual(8)
    })
  })

  test.describe('write progress', () => {
    test('a switch that saves keeps its colour and its size and shows a spinner', async () => {
      const slow = await provisionTenantFixture({ prefix: 'e2e-busy' })
      await platform.setViewportSize(DESKTOP)
      await openTenantSheet(slow.code, slow.tenantId)
      const control = platform.getByRole('dialog').getByRole('switch').first()
      const before = await control.boundingBox()
      await platform.route(
        (url) => url.pathname.includes('/apps/'),
        async (route) => {
          if (route.request().method() !== 'PUT') return route.fallback()
          await new Promise((resolve) => setTimeout(resolve, 1_800))
          return route.continue()
        },
      )
      try {
        await control.click()
        await expect(control).toHaveAttribute('aria-busy', 'true')
        await platform.waitForTimeout(500)
        const during = await control.evaluate((element) => ({
          opacity: Number(getComputedStyle(element).opacity),
          spinner: element.querySelector('.animate-spin') !== null,
          box: element.getBoundingClientRect().toJSON() as { x: number; y: number; width: number; height: number },
        }))
        expect(during.opacity, 'a saving switch looks disabled').toBe(1)
        expect(during.spinner, 'a saving switch shows no spinner').toBe(true)
        expect(during.box.width).toBeCloseTo(before?.width ?? 0, 0)
        expect(during.box.height).toBeCloseTo(before?.height ?? 0, 0)
        expect(during.box.x).toBeCloseTo(before?.x ?? 0, 0)
        await expect(control).not.toHaveAttribute('aria-busy', 'true', { timeout: 10_000 })
      } finally {
        await platform.unrouteAll({ behavior: 'ignoreErrors' })
      }
    })

    test('a button in progress keeps its width', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'customers')
      await openFirstRecord(billing, false)
      await billing
        .getByRole('dialog')
        .first()
        .getByRole('button', { name: /^Edit$/ })
        .click()
      const save = billing.getByRole('dialog').last().getByRole('button', { name: 'Save', exact: true })
      // The dialog scales in; its size is read once it has arrived.
      await billing.waitForTimeout(450)
      const before = (await save.boundingBox())?.width ?? 0
      await billing.route(
        (url) => url.pathname.includes('/customers/'),
        async (route) => {
          if (route.request().method() === 'GET') return route.fallback()
          await new Promise((resolve) => setTimeout(resolve, 1_500))
          return route.continue()
        },
      )
      try {
        await save.click()
        await expect(save).toHaveAttribute('aria-busy', 'true')
        await billing.waitForTimeout(300)
        const during = (await save.boundingBox())?.width ?? 0
        expect(before).toBeGreaterThan(0)
        expect(during).toBeCloseTo(before, 0)
        await expect(billing.getByRole('dialog')).toHaveCount(1, { timeout: 10_000 })
      } finally {
        await billing.unrouteAll({ behavior: 'ignoreErrors' })
      }
      await billing.keyboard.press('Escape')
    })
  })

  test.describe('write failure', () => {
    test('a failed save says why inside the dialog and in a toast above the sheet, and the next opening is clean', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'customers')
      await openFirstRecord(billing, false)
      const edit = billing
        .getByRole('dialog')
        .first()
        .getByRole('button', { name: /^Edit$/ })
      await edit.click()
      const dialog = billing.getByRole('dialog').last()
      await billing.route(
        (url) => url.pathname.includes('/customers/'),
        (route) =>
          route.request().method() === 'GET'
            ? route.fallback()
            : route.fulfill({
                status: 409,
                contentType: 'application/problem+json',
                body: JSON.stringify({ title: 'Conflict', detail: 'That display name is already taken.' }),
              }),
      )
      try {
        await dialog.getByRole('button', { name: 'Save', exact: true }).click()
        await expect(dialog.getByText('That display name is already taken.')).toBeVisible()
        await expect(billing.locator(TOAST).filter({ hasText: 'already taken' })).toBeVisible()
        await billing.waitForTimeout(300)
        expect(await isOnTop(billing, TOAST), 'the failure toast is covered').toBe(true)
        await dialog.getByRole('button', { name: 'Cancel' }).click()
        await billing.waitForTimeout(400)
        await edit.click()
        await billing.getByRole('dialog').last().waitFor()
        await expect(billing.getByRole('dialog').last().getByText('already taken')).toHaveCount(0)
      } finally {
        await billing.unrouteAll({ behavior: 'ignoreErrors' })
      }
      await billing.keyboard.press('Escape')
      await billing.waitForTimeout(300)
    })
  })

  test.describe('read failure inside a sheet', () => {
    test('a failed detail shows the sentence and Retry in the section, never the empty mark, and Retry recovers', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const detail = (url: URL) => /\/api\/v1\/invoices\/[0-9a-f-]{36}$/.test(url.pathname)
      await billing.route(detail, (route) =>
        route.fulfill({
          status: 500,
          contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'Boom', detail: 'The ledger service is down.' }),
        }),
      )
      try {
        await openFirstRecord(billing, false)
        const items = billing.getByRole('dialog').first().locator('section').filter({ hasText: 'Items' }).first()
        await expect(items.getByText('The ledger service is down.')).toBeVisible()
        await expect(items.getByRole('button', { name: 'Retry' })).toBeVisible()
        await expect(items.getByText('—')).toHaveCount(0)
        await billing.unroute(detail)
        await items.getByRole('button', { name: 'Retry' }).click()
        await expect(items.getByText('The ledger service is down.')).toHaveCount(0, { timeout: 10_000 })
      } finally {
        await billing.unrouteAll({ behavior: 'ignoreErrors' })
      }
      await billing.keyboard.press('Escape')
      await billing.waitForTimeout(300)
    })
  })

  test.describe('list failure and refresh', () => {
    const list = (url: URL) => url.pathname.endsWith('/api/v1/invoices')

    test('a failed list says what the server said', async () => {
      await billing.setViewportSize(DESKTOP)
      await billing.route(list, (route) =>
        route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'Unavailable', detail: 'The replica is behind.' }),
        }),
      )
      try {
        await openRemote(billing, tenant.host, 'billing', 'invoices')
        await expect(billing.getByText(/The replica is behind\./)).toBeVisible({ timeout: 15_000 })
        await expect(billing.getByRole('button', { name: 'Retry' })).toBeVisible()
      } finally {
        await billing.unrouteAll({ behavior: 'ignoreErrors' })
      }
    })

    test('a list that refreshes shows a bar within 300ms of the request, and a fast answer shows none', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const search = billing.getByRole('textbox', { name: /^Search/ })
      await billing.evaluate(() => {
        const seen: { barAt: number | null } = { barAt: null }
        ;(window as unknown as { __bar: typeof seen }).__bar = seen
        new MutationObserver(() => {
          if (seen.barAt === null && document.querySelector('.animate-progress')) seen.barAt = Date.now()
        }).observe(document.body, { childList: true, subtree: true })
      })
      const barAt = () => billing.evaluate(() => (window as unknown as { __bar: { barAt: number | null } }).__bar.barAt)

      // An answer that comes at once, whatever the machine is doing: no bar.
      await billing.route(list, (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], page: { limit: 50, offset: 0, total: 0 } }),
        }),
      )
      try {
        await search.fill('a')
        await billing.waitForTimeout(700)
        expect(await barAt(), 'a fast answer showed the bar').toBeNull()
      } finally {
        await billing.unrouteAll({ behavior: 'ignoreErrors' })
      }

      // A slow one: the bar shows 150ms after the request left, with slack for a busy machine.
      let requestedAt = 0
      await billing.route(list, async (route) => {
        requestedAt = Date.now()
        await new Promise((resolve) => setTimeout(resolve, 800))
        return route.continue()
      })
      try {
        await search.fill('b')
        await billing.waitForSelector('.animate-progress', { timeout: 2_000 })
        const shown = await barAt()
        expect(shown, 'the bar never showed').not.toBeNull()
        expect((shown ?? 0) - requestedAt, 'the bar took too long after the request').toBeLessThan(300)
        await expect(billing.locator('table[aria-busy=true]')).toHaveCount(1)
        await expect(billing.locator('.animate-progress')).toHaveCount(0, { timeout: 5_000 })
      } finally {
        await billing.unrouteAll({ behavior: 'ignoreErrors' })
      }
      await search.fill('')
    })
  })

  test.describe('search requests and facet rows', () => {
    test('typing a word of eight characters sends at most two list requests and ends with the whole word', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const requests: string[] = []
      const onRequest = (request: { method: () => string; url: () => string }) => {
        if (request.method() === 'GET' && new URL(request.url()).pathname.endsWith('/api/v1/invoices')) {
          requests.push(request.url())
        }
      }
      billing.on('request', onRequest)
      try {
        const search = billing.getByRole('textbox', { name: /^Search/ })
        await search.click()
        await search.pressSequentially('INV-0001', { delay: 40 })
        await billing.waitForTimeout(900)
        expect(await search.inputValue()).toBe('INV-0001')
        expect(requests.length, `list requests: ${requests.length}`).toBeLessThanOrEqual(2)
        expect(new URL(billing.url()).searchParams.get('q')).toBe('INV-0001')
      } finally {
        billing.off('request', onRequest)
      }
      await billing.getByRole('textbox', { name: /^Search/ }).fill('')
      await billing.waitForTimeout(500)
    })

    test('clicking the text of a facet option toggles it', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'customers')
      await billing
        .getByRole('button', { name: /Status/ })
        .first()
        .click()
      const option = billing.locator('[data-radix-popper-content-wrapper] label').filter({ hasText: 'Active' }).first()
      await option.getByText('Active', { exact: true }).click({ timeout: 5_000 })
      await expect.poll(() => new URL(billing.url()).searchParams.get('f_status')).not.toBeNull()
      await expect(billing.getByRole('button', { name: /Remove the Status/ }).first()).toBeVisible()
      await billing.keyboard.press('Escape')
      await openScreen('billing', 'customers')
    })
  })

  test.describe('rendering cost', () => {
    const results: Record<string, unknown> = {}
    const acceptable = (name: string, stats: Awaited<ReturnType<typeof frameStats>>) => {
      results[name] = stats
      console.log(`PERF ${name} ${JSON.stringify(stats)}`)
      expect(stats.framesOver50ms, `${name}: frames over 50ms`).toBe(0)
      expect(stats.framesOver33ms, `${name}: frames over 33ms`).toBeLessThanOrEqual(2)
      expect(stats.longTasks, `${name}: long tasks`).toBe(0)
    }

    test('opening a record sheet', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const stats = await frameStats(billing, async () => {
        await billing.locator('tbody tr').first().locator('td').first().click()
        await billing.getByRole('dialog').first().waitFor()
        await billing.waitForTimeout(450)
      })
      acceptable('open-sheet', stats)
      await billing.keyboard.press('Escape')
      await billing.waitForTimeout(400)
    })

    test('scrolling a long list', async () => {
      await platform.setViewportSize(DESKTOP)
      await openScreen('platform', 'tenants')
      const box = await platform.locator('main').boundingBox()
      await platform.mouse.move((box?.x ?? 0) + 300, (box?.y ?? 0) + 300)
      const stats = await frameStats(platform, async () => {
        for (let step = 0; step < 12; step++) {
          await platform.mouse.wheel(0, 250)
          await platform.waitForTimeout(40)
        }
      })
      acceptable('scroll-list', stats)
    })

    test('switching the theme', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const stats = await frameStats(billing, async () => {
        await billing.getByRole('button', { name: 'Theme' }).click()
        await billing.getByRole('menuitemradio', { name: 'Dark' }).click()
        await billing.waitForTimeout(400)
      })
      acceptable('switch-theme', stats)
      await billing.getByRole('button', { name: 'Theme' }).click()
      await billing.getByRole('menuitemradio', { name: 'Light' }).click()
    })

    test('typing a search word', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      let requests = 0
      const onRequest = (request: { method: () => string; url: () => string }) => {
        if (request.method() === 'GET' && new URL(request.url()).pathname.endsWith('/api/v1/invoices')) requests++
      }
      billing.on('request', onRequest)
      const search = billing.getByRole('textbox', { name: /^Search/ })
      await search.click()
      const stats = await frameStats(billing, async () => {
        await search.pressSequentially('INV-0001', { delay: 40 })
        await billing.waitForTimeout(700)
      })
      billing.off('request', onRequest)
      console.log(`PERF type-search requests ${requests}`)
      acceptable('type-search', stats)
      expect(requests).toBeLessThanOrEqual(2)
      await search.fill('')
      await billing.waitForTimeout(500)
    })
  })

  test.describe('icons', () => {
    test('the theme control shows the choice made, its three items have three icons and the current one is checked', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'invoices')
      const glyph = (scope: ReturnType<Page['locator']>) =>
        scope
          .locator('svg')
          .first()
          .evaluate((svg) => [...svg.classList].find((name) => name.startsWith('lucide-')) ?? '')
      const trigger = billing.getByRole('button', { name: 'Theme' })
      const seen = new Map<string, string>()
      for (const [name, expected] of [
        ['Dark', 'lucide-moon'],
        ['Light', 'lucide-sun'],
        ['System', 'lucide-monitor'],
      ] as const) {
        await trigger.click()
        await billing.getByRole('menuitemradio', { name }).click()
        await billing.waitForTimeout(200)
        expect(await glyph(trigger), `the trigger after choosing ${name}`).toBe(expected)
        await trigger.click()
        await expect(billing.getByRole('menuitemradio', { name })).toHaveAttribute('aria-checked', 'true')
        for (const item of ['Light', 'Dark', 'System']) {
          seen.set(item, await glyph(billing.getByRole('menuitemradio', { name: item })))
        }
        await billing.keyboard.press('Escape')
      }
      expect(new Set(seen.values()).size, `the three items share icons: ${[...seen.values()].join(', ')}`).toBe(3)
    })

    test('no navigation item shares an icon with another in the same app', async () => {
      for (const [page, host, remote] of [
        [platform, env.platformHost, 'platform'],
        [billing, tenant.host, 'billing'],
      ] as const) {
        await openRemote(page, host, remote, '')
        await settle(page)
        const icons = await page
          .locator('aside nav a > svg')
          .evaluateAll((svgs) => svgs.map((svg) => [...svg.classList].find((name) => name.startsWith('lucide-')) ?? ''))
        // Only the screens' own glyphs: the app rows draw a mark inside a tile, one level deeper.
        const unique = new Set(icons.filter(Boolean))
        expect(unique.size, `${remote}: ${icons.join(', ')}`).toBe(icons.filter(Boolean).length)
      }
    })
  })

  test.describe('alignment', () => {
    test('every version of a remote is as tall as the others and its button or badge sits on the row centre', async () => {
      await platform.setViewportSize(DESKTOP)
      await openScreen('platform', 'remotes')
      await platform.locator('tbody tr').first().locator('td').first().click()
      const sheet = platform.getByRole('dialog').first()
      await sheet.waitFor()
      await platform.waitForTimeout(450)
      const rows = await sheet
        .locator('section')
        .filter({ hasText: 'Versions' })
        .first()
        .locator('li')
        .evaluateAll((items) =>
          items.map((item) => {
            const box = item.getBoundingClientRect()
            const trailing = item.querySelector('button, [class*=rounded-full]') as HTMLElement | null
            const mark = trailing?.getBoundingClientRect()
            return {
              height: box.height,
              offset: mark ? Math.abs(mark.top + mark.height / 2 - (box.top + box.height / 2)) : null,
            }
          }),
        )
      expect(rows.length, 'the remote has fewer than two versions').toBeGreaterThanOrEqual(2)
      const heights = new Set(rows.map((row) => Math.round(row.height)))
      expect([...heights], `row heights ${rows.map((row) => row.height).join(', ')}`).toHaveLength(1)
      for (const row of rows) expect(row.offset ?? 0).toBeLessThanOrEqual(1)
      await platform.keyboard.press('Escape')
      await platform.waitForTimeout(400)
    })

    test('the label and the value of a detail row share a baseline on single-line rows', async () => {
      const faults: Fault[] = []
      let checked = 0
      for (const [app, section] of [
        ['billing', 'customers'],
        ['billing', 'invoices'],
        ['billing', 'wallets'],
        ['platform', 'tenants'],
        ['platform', 'remotes'],
      ] as const) {
        const page = pageFor(app)
        await page.setViewportSize(DESKTOP)
        await openScreen(app, section)
        await openFirstRecord(page, false)
        const differences = await page.evaluate(() => {
          // The baseline of the first line of text: a zero-size inline box dropped in front of it sits on the baseline.
          const baseline = (element: Element): number | null => {
            const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              if (!node.textContent?.trim()) continue
              const range = document.createRange()
              range.selectNodeContents(node)
              const rect = range.getBoundingClientRect()
              if (rect.height <= 0 || rect.height >= 26) return null
              const parent = node.parentNode as HTMLElement
              // A flex or grid parent turns the probe into an item, which says nothing about the text's baseline.
              if (/flex|grid/.test(getComputedStyle(parent).display)) return null
              const probe = document.createElement('span')
              probe.style.cssText = 'display:inline-block;width:0;height:0'
              parent.insertBefore(probe, node)
              const at = probe.getBoundingClientRect().bottom
              probe.remove()
              return at
            }
            return null
          }
          const out: number[] = []
          for (const row of document.querySelectorAll('[role=dialog] dl > div')) {
            const label = row.querySelector('dt')
            const value = row.querySelector('dd')
            if (!label || !value) continue
            const a = baseline(label)
            const b = baseline(value)
            if (a !== null && b !== null) out.push(Math.abs(a - b))
          }
          return out
        })
        checked += differences.length
        for (const difference of differences) {
          if (difference > 1)
            faults.push({ screen: `${app}/${section}`, detail: `label and value are ${difference}px apart` })
        }
        await page.keyboard.press('Escape')
        await page.waitForTimeout(400)
      }
      console.log(`detail rows measured: ${checked}`)
      expect(describeFaults(faults)).toBe('')
      expect(checked, 'no detail row was measured').toBeGreaterThanOrEqual(8)
    })
  })

  test.describe('one look for one control', () => {
    test('a select in a dialog is as tall as the input beside it', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'coupons')
      await billing.getByRole('button', { name: 'Create coupon' }).first().click()
      const dialog = billing.getByRole('dialog').last()
      await dialog.waitFor()
      await billing.waitForTimeout(450)
      const heights = await dialog.evaluate((element) => ({
        select: element.querySelector('select')?.getBoundingClientRect().height ?? 0,
        input: element.querySelector('input:not([type=checkbox])')?.getBoundingClientRect().height ?? 0,
        chevron: element.querySelector('select ~ span svg') !== null,
      }))
      expect(heights.select).toBeGreaterThan(0)
      expect(heights.select).toBeCloseTo(heights.input, 0)
      expect(heights.chevron, 'the select draws no chevron').toBe(true)
      await billing.keyboard.press('Escape')
      await billing.waitForTimeout(400)
    })

    test('hovering a copyable id moves nothing beside it', async () => {
      await billing.setViewportSize(DESKTOP)
      await openScreen('billing', 'customers')
      await openFirstRecord(billing, false)
      const copy = billing
        .getByRole('dialog')
        .first()
        .getByRole('button', { name: /^Copy / })
        .first()
      const text = copy.locator('span.truncate')
      await billing.mouse.move(0, 0)
      const before = await text.boundingBox()
      await copy.hover()
      await billing.waitForTimeout(300)
      const after = await text.boundingBox()
      expect(before).not.toBeNull()
      expect(after?.width).toBeCloseTo(before?.width ?? 0, 0)
      expect(after?.x).toBeCloseTo(before?.x ?? 0, 0)
      await billing.keyboard.press('Escape')
      await billing.waitForTimeout(400)
    })
  })

  test.describe('Vietnamese strings', () => {
    test('with the language set to Vietnamese, no accessible name is left in English', async () => {
      await billing.setViewportSize(DESKTOP)
      await openRemote(billing, tenant.host, 'billing', 'customers?f_status=ACTIVE')
      await settle(billing)
      await billing.getByRole('button', { name: /^Language/ }).click()
      await billing.getByRole('menuitemradio', { name: 'Vietnamese' }).click()
      await billing.waitForTimeout(500)
      try {
        const names = await billing.evaluate(() =>
          [...document.querySelectorAll('[aria-label]')].map((element) => element.getAttribute('aria-label') ?? ''),
        )
        const leftovers = names.filter((name) => /^(Actions|Breadcrumb)$|^Remove the /.test(name))
        expect(leftovers, 'accessible names still in English').toEqual([])
        expect(names.length).toBeGreaterThan(5)
      } finally {
        await billing.getByRole('button', { name: /^Ngôn ngữ/ }).click()
        await billing.getByRole('menuitemradio', { name: 'Tiếng Anh' }).click()
        await billing.waitForTimeout(400)
      }
    })
  })

  test.describe('the first entitlement', () => {
    test('switching on an app a tenant never changed succeeds and stays on', async () => {
      const fresh = await provisionTenantFixture({ prefix: 'e2e-first' })
      await platform.setViewportSize(DESKTOP)
      await openTenantSheet(fresh.code, fresh.tenantId)
      const sheet = platform.getByRole('dialog')
      const first = sheet.getByRole('switch').first()
      await expect(first).not.toBeChecked()
      await first.click()
      await expect(first).toBeChecked()
      // The write has answered once the switch is no longer busy.
      await expect(first).toBeEnabled({ timeout: 10_000 })
      await platform.waitForTimeout(500)
      await expect(platform.getByRole('status').filter({ hasText: /changed since/i })).toHaveCount(0)
      await expect(first).toBeChecked()

      await platform.reload()
      await platform.getByRole('dialog').waitFor({ timeout: 15_000 })
      await expect(platform.getByRole('dialog').getByRole('switch').first()).toBeChecked()
    })
  })
})
