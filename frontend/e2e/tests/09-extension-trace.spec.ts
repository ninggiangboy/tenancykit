import { cpSync, existsSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { buildRemote, psql, publishRemote, removePublishedFiles, versionFor } from '../support/deploy'
import { frontendDir } from '../support/env'
import { env, expect, sidebarApps, signIn, test } from '../support/fixtures'
import { findRemote } from '../support/platform'
import { ensureRemoteActive, platformAdminToken, provisionTenantFixture, waitForBilling } from '../support/stack'

/**
 * The extension trace: a second remote built from the billing template, published and registered like any other,
 * appears for its audience without a shell change. Then its row and its files are removed, which is the whole
 * uninstall too.
 */
test('a second remote from the same template needs no shell change', async ({ page }) => {
  test.setTimeout(900_000)

  const name = `e2e-extra-${Date.now().toString(36).slice(-4)}`
  const source = join(frontendDir, 'remotes', 'billing')
  const directory = join(frontendDir, 'remotes', name)
  const version = versionFor('extra')

  try {
    cpSync(source, directory, {
      recursive: true,
      filter: (path) => !path.includes(`${join('billing', 'dist')}`) && !path.endsWith('node_modules'),
    })
    // The workspace was installed before this directory existed, so the copy borrows the template's links.
    symlinkSync(join(source, 'node_modules'), join(directory, 'node_modules'), 'dir')

    const manifest = JSON.parse(readFileSync(join(directory, 'package.json'), 'utf8')) as {
      name: string
      console?: Record<string, unknown>
    }
    manifest.name = `@suite/remote-${name}`
    manifest.console = { appCode: 'billing', title: 'E2E extra', audiences: ['TENANT'], navOrder: 90 }
    writeFileSync(join(directory, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`)

    const viteConfig = readFileSync(join(directory, 'vite.config.ts'), 'utf8')
    writeFileSync(join(directory, 'vite.config.ts'), viteConfig.replace("name: 'billing'", `name: '${name}'`))

    buildRemote(name, version)
    publishRemote(name, version)

    const admin = await platformAdminToken()
    expect((await findRemote(admin, name)).versions.map((entry) => entry.version)).toContain(version)
    await ensureRemoteActive(name)

    const tenant = await provisionTenantFixture({ billing: true, prefix: 'e2e-ext' })
    await waitForBilling(tenant.host, tenant.token)
    await signIn(page, tenant.host, tenant.adminEmail, tenant.adminPassword)
    await expect.poll(async () => sidebarApps(page), { timeout: 60_000 }).toContain('E2E extra')
    expect(await sidebarApps(page)).toContain('Billing')
    expect(env.platformHost).not.toBe(tenant.host)
  } finally {
    if (existsSync(directory)) {
      rmSync(directory, { recursive: true, force: true })
    }
    psql('platform', `update ui_remotes set active_version = null where name = '${name}'`)
    psql('platform', `delete from ui_remote_versions where remote_name = '${name}'`)
    psql('platform', `delete from ui_remotes where name = '${name}'`)
    try {
      removePublishedFiles(name, version)
    } catch {
      // The directory is only there when the publish got as far as copying; a missing one is already clean.
    }
  }

  // Removed, not merely disabled: the row, its versions and its files are gone.
  expect(existsSync(directory)).toBe(false)
  const remotes = await fetch(`${env.originFor(env.platformHost)}/platform/api/v1/ui-remotes?limit=100`, {
    headers: { Authorization: `Bearer ${await platformAdminToken()}` },
  }).then(async (answer) => ((await answer.json()) as { data: Array<{ name: string }> }).data)
  expect(remotes.map((entry) => entry.name)).not.toContain(name)
})
