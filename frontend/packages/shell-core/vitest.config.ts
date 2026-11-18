import { defineConfig } from 'vitest/config'

export default defineConfig({
  // TanStack Router picks its browser or server build from these conditions; jsdom is a browser.
  resolve: { conditions: ['browser', 'development'] },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test-setup.ts'],
    restoreMocks: true,
    // Interaction tests on a busy machine (several packages building at once) need longer than the 5 s default.
    testTimeout: 20_000,
    // A production NODE_ENV in the caller's shell must not swap React for its production build.
    env: { NODE_ENV: 'test' },
  },
})
