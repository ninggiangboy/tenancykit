import { env, expect, test } from '../support/fixtures'
import { signIn } from '../support/console'
import { linkInMailTo, mailCount } from '../support/mail'
import { provisionTenantFixture } from '../support/stack'

/**
 * A person who forgot the password asks for a reset on their own tenant's host and types only the email. Keycloak
 * renders the mail, hands it to mailer through the broker, and mailer delivers it; the link chooses a new password.
 * An email with no account gets the same answer and no mail, so the page does not say who has an account.
 */
test('a person resets a forgotten password from a mailed link, and an unknown email gets no mail', async ({
  page,
  browser,
}) => {
  test.setTimeout(240_000)

  const tenant = await provisionTenantFixture({ prefix: 'e2e-fp' })
  const newPassword = `${tenant.adminPassword}-new`

  // The sign-in page offers the reset, and the reset page asks for an email and says which host it is for.
  await page.goto(env.consoleUrl(tenant.host))
  await page.getByRole('link', { name: /forgot password/i }).click()
  await expect(page.getByRole('heading', { name: /forgot your password/i })).toBeVisible()
  await expect(page.getByLabel('Email')).toBeVisible()
  await expect(page.getByText(new RegExp(`sign in to ${tenant.host}`))).toBeVisible()

  await page.getByLabel('Email').fill(tenant.adminEmail)
  await page.getByRole('button', { name: 'Submit' }).click()
  await expect(page.getByText(/you should receive an email/i)).toBeVisible()

  // The mail reaches the inbox through the broker and mailer, and its link chooses a new password.
  const link = await linkInMailTo(tenant.adminEmail, /http\S*action-token\S*/)
  expect(await mailCount(tenant.adminEmail)).toBe(1)
  await page.goto(link)
  await page.locator('#password-new').fill(newPassword)
  await page.locator('#password-confirm').fill(newPassword)
  await page.getByRole('button', { name: 'Submit' }).click()

  // The new password opens the tenant; the old one no longer does.
  const fresh = await browser.newContext()
  const freshPage = await fresh.newPage()
  await signIn(freshPage, tenant.host, tenant.adminEmail, newPassword)
  await fresh.close()

  const stale = await browser.newContext()
  const stalePage = await stale.newPage()
  await stalePage.goto(env.consoleUrl(tenant.host))
  await stalePage.locator('#username').fill(tenant.adminEmail)
  await stalePage.locator('#password').fill(tenant.adminPassword)
  await stalePage.locator('#kc-login').click()
  await expect(stalePage.getByText('Invalid username or password.')).toBeVisible()
  await stale.close()

  // An email with no account is answered the same way and nothing is sent.
  const stranger = `nobody-${Date.now()}@example.test`
  const other = await browser.newContext()
  const otherPage = await other.newPage()
  await otherPage.goto(env.consoleUrl(tenant.host))
  await otherPage.getByRole('link', { name: /forgot password/i }).click()
  await otherPage.getByLabel('Email').fill(stranger)
  await otherPage.getByRole('button', { name: 'Submit' }).click()
  await expect(otherPage.getByText(/you should receive an email/i)).toBeVisible()
  await new Promise((done) => setTimeout(done, 3_000))
  expect(await mailCount(stranger)).toBe(0)
  await other.close()
})
