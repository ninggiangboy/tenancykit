import { env, expect, keycloakUsername, signIn, test } from '../support/fixtures'
import { makeLoginable } from '../support/keycloak'
import { createUser } from '../support/platform'
import { platformAdminToken, provisionTenantFixture } from '../support/stack'

/**
 * The console names the tenant from its host, so Keycloak's form asks for an email and nothing else. The stored
 * username stays `<tenant>:<email>`, which is what lets one email be two independent people in two tenants: this test
 * makes that case and checks each host reaches only its own person.
 */
test('a person signs in with an email only, and the same email in two tenants stays two people', async ({
  page,
  browser,
}) => {
  test.setTimeout(240_000)

  const first = await provisionTenantFixture({ prefix: 'e2e-sia' })
  const second = await provisionTenantFixture({ prefix: 'e2e-sib' })

  const sharedEmail = first.adminEmail
  const secondPassword = `${second.adminPassword}-b`
  await createUser(await platformAdminToken(), {
    tenantCode: second.code,
    email: sharedEmail,
    displayName: 'Same Email',
    temporaryPassword: 'Temporary-2',
  })
  await makeLoginable(keycloakUsername(second.code, sharedEmail), secondPassword)

  // The form says where the person is signing in and asks for an email, never for a tenant prefix.
  await page.goto(env.consoleUrl(first.host))
  await expect(page.getByRole('heading', { name: `Sign in to ${first.host}` })).toBeVisible()
  await expect(page.getByLabel('Email')).toBeVisible()
  await signIn(page, first.host, sharedEmail, first.adminPassword)

  // The platform's own host is a tenant of its own, `platform`, and asks the same way.
  const operator = await browser.newContext()
  const operatorPage = await operator.newPage()
  await operatorPage.goto(env.consoleUrl(env.platformHost))
  await expect(operatorPage.getByRole('heading', { name: `Sign in to ${env.platformHost}` })).toBeVisible()
  await operator.close()

  // The same email at the second tenant is a different person with a different password.
  const other = await browser.newContext()
  const otherPage = await other.newPage()
  await signIn(otherPage, second.host, sharedEmail, secondPassword)
  await other.close()

  // The second tenant's password does not open the first tenant, and the page still says where it is.
  const wrong = await browser.newContext()
  const wrongPage = await wrong.newPage()
  await wrongPage.goto(env.consoleUrl(first.host))
  await wrongPage.locator('#username').fill(sharedEmail)
  await wrongPage.locator('#password').fill(secondPassword)
  await wrongPage.locator('#kc-login').click()
  await expect(wrongPage.getByText('Invalid username or password.')).toBeVisible()
  await expect(wrongPage.getByRole('heading', { name: `Sign in to ${first.host}` })).toBeVisible()
  await wrong.close()
})
