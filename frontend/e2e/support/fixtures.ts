import { test as base, expect } from '@playwright/test'
import { type ConsoleHealth, recordCspViolations, watchConsole } from './health'

/**
 * Every browser test starts with the console health rule attached, so a CSP violation or a failed request during the
 * suite fails the test that saw it rather than hiding in a log.
 */
export const test = base.extend<{ health: ConsoleHealth }>({
  health: async ({ page }, use) => {
    await recordCspViolations(page)
    const health = watchConsole(page)
    await use(health)
  },
})

export { expect }

/** For a test that provokes a refusal on purpose and asserts it instead of treating it as a fault. */
export const rawTest = base
export const rawExpect = expect

export * from './console'
export { env, keycloakUsername, uniqueCode } from './env'
export { ensureRemoteActive, platformAdminToken, provisionTenantFixture, seedBilling } from './stack'
