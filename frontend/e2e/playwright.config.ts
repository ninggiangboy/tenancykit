import { defineConfig } from '@playwright/test'

/**
 * The suite runs against the compose stack through Caddy, never against dev servers, because the point is the deployed
 * console: two hosts, the registry feed and the remote the operator activated. One worker, because a spec that
 * publishes, activates or disables a remote changes what every other spec would see.
 */
export default defineConfig({
  testDir: './tests',
  globalSetup: './support/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  use: {
    trace: process.env.CI ? 'off' : 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
