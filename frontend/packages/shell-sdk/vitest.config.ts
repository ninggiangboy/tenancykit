import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test-setup.ts'],
    restoreMocks: true,
    // Interaction tests on a busy machine (several packages building at once) need longer than the 5 s default.
    testTimeout: 20_000,
    env: { NODE_ENV: 'test' },
  },
})
