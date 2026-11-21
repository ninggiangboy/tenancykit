import { expect, type Page } from '@playwright/test'
import { env } from './env'

/** Waits for the shell's frame; the sidebar exists before any remote has mounted. */
export async function waitForShell(page: Page): Promise<void> {
  await page.locator('aside').first().waitFor({ state: 'visible', timeout: 30_000 })
}

/**
 * Signs in the way a person does: the shell sends an unauthenticated visitor to Keycloak, and the callback brings the
 * session back. A reload later goes through Keycloak's own session silently, which is what the console's in-memory
 * tokens cost.
 */
export async function signIn(page: Page, host: string, username: string, password: string): Promise<void> {
  await page.goto(env.consoleUrl(host))
  // The shell either resolves the Keycloak session on its own or sends the browser to the sign-in form; both can
  // happen a moment after the first document, so neither the URL nor a single locator decides.
  const form = page.locator('#username')
  const frame = page.locator('aside').first()
  await Promise.race([
    form.waitFor({ state: 'visible', timeout: 30_000 }).catch(() => undefined),
    frame.waitFor({ state: 'visible', timeout: 30_000 }).catch(() => undefined),
  ])
  if (await form.isVisible()) {
    await form.fill(username)
    await page.locator('#password').fill(password)
    await page.locator('#kc-login').click()
    // The callback page is not the shell yet: the boot finishes on `/console/`, so wait for that rather than for the
    // first URL that is not Keycloak's.
    await page.waitForURL((url) => url.pathname.startsWith('/console') && !url.pathname.includes('/callback'), {
      timeout: 30_000,
    })
  }
  await waitForShell(page)
}

/** A remote's own route under the shell: `/console/r/<remote>/<section>`. */
export async function openRemote(page: Page, host: string, remote: string, section = ''): Promise<void> {
  const suffix = section ? `${section.replace(/^\//, '')}` : ''
  await page.goto(`${env.consoleUrl(host)}r/${remote}/${suffix}`)
  await page.locator(`[data-remote="${remote}"]`).waitFor({ state: 'visible', timeout: 30_000 })
}

/** The version the shell mounted, which is the registry's active version rather than whatever the last build was. */
export async function mountedVersion(page: Page, remote: string): Promise<string | null> {
  const container = page.locator(`[data-remote="${remote}"]`)
  await container.waitFor({ state: 'visible', timeout: 30_000 })
  return container.getAttribute('data-remote-version')
}

/** The app names in the shell's sidebar. */
export async function sidebarApps(page: Page): Promise<string[]> {
  const nav = page.locator('aside nav').first()
  await nav.waitFor({ state: 'visible', timeout: 30_000 })
  return (await nav.locator('a').allInnerTexts()).map((text) => text.trim()).filter(Boolean)
}

/**
 * Selects the row j moved to. A list that is still refreshing can replace its rows between the move and the
 * selection, which drops the active key; the pair is retried until the BulkBar shows the selection.
 */
export async function selectActiveRow(page: Page, label = '1 selected'): Promise<void> {
  await expect(async () => {
    await page.keyboard.press('j')
    await page.keyboard.press('x')
    await expect(page.locator(`section[aria-label="${label}"]`)).toBeVisible({ timeout: 1_500 })
  }).toPass({ timeout: 20_000 })
}

/** Switches the shell's language through the header menu, as a person does. */
export async function switchLanguage(page: Page, language: 'English' | 'Vietnamese'): Promise<void> {
  await page.getByRole('button', { name: 'Language' }).click()
  await page.getByRole('menuitemradio', { name: language }).click()
}
