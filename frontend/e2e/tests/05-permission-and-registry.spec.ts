import { env, expect, openRemote, rawExpect, signIn, test } from '../support/fixtures'
import { call, listRoles, replaceRolePermissions } from '../support/platform'
import { platformAdminToken, provisionTenantFixture, seedBilling, waitForBilling } from '../support/stack'

/**
 * A control the caller may not use is absent from the screen, and the backend refuses the same action when it is
 * called directly. Billing's own 403 is what makes the second half true; the missing button is only the courtesy.
 */
test('a permission the caller lacks hides the control and is refused by the backend', async ({ page }) => {
  test.setTimeout(180_000)

  const tenant = await provisionTenantFixture({ billing: true, prefix: 'e2e-perm' })
  await waitForBilling(tenant.host, tenant.token)
  const seeded = await seedBilling(tenant)

  await signIn(page, tenant.host, tenant.adminEmail, tenant.adminPassword)
  await openRemote(page, tenant.host, 'billing', 'invoices')
  await expect(page.getByRole('button', { name: 'Create invoice' })).toBeVisible()

  // The tenant administrator keeps every permission but the one that creates an invoice from a charge.
  const admin = await platformAdminToken()
  const role = (await listRoles(admin, tenant.tenantId)).find((entry) => entry.name === 'Tenant Administrator')
  if (!role) throw new Error(`tenant ${tenant.code} has no Tenant Administrator role`)
  const withoutCreate = role.permissions.filter((code) => code !== 'billing:invoice:create')
  rawExpect(withoutCreate.length).toBe(role.permissions.length - 1)
  await replaceRolePermissions(admin, role, withoutCreate)

  await expect
    .poll(
      async () =>
        (
          await call('/api/v1/invoices', {
            method: 'POST',
            token: tenant.token,
            host: tenant.host,
            body: { chargeId: seeded.chargeId },
          })
        ).status,
      { timeout: 60_000 },
    )
    .toBe(403)

  await page.reload()
  await expect(page.getByRole('heading', { name: 'Invoices' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create invoice' })).toHaveCount(0)
})

/** The registry is a code-injection surface: an entry path outside the version directory is refused and never stored. */
test('the registry refuses a version whose entry path is outside its directory', async () => {
  const minted = await fetch(`${env.originFor(env.platformHost)}/platform/oauth/token`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(`${env.frontendCi.id}:${env.frontendCi.secret}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({ grant_type: 'client_credentials', audience: 'platform' }),
  })
  if (!minted.ok) {
    throw new Error(`the frontend-ci client could not get a token: ${minted.status} ${await minted.text()}`)
  }
  const { access_token: token } = (await minted.json()) as { access_token: string }

  const outside = await call('/platform/api/v1/ui-remotes/billing/versions', {
    method: 'POST',
    token,
    body: {
      version: 'e2e-outside',
      entryPath: '/console/remotes/somewhere/else/mf-manifest.json',
      integrity: `sha384-${'A'.repeat(64)}`,
      sdkMajor: 1,
    },
  })
  rawExpect(outside.status).toBe(400)

  const listing = await call<{ data: Array<{ name: string; versions: Array<{ version: string }> }> }>(
    '/platform/api/v1/ui-remotes?limit=100',
    { token: await platformAdminToken() },
  )
  const billing = listing.body.data.find((entry) => entry.name === 'billing')
  rawExpect(billing?.versions.some((entry) => entry.version === 'e2e-outside')).toBeFalsy()
})
