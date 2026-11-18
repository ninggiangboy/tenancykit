import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test-setup.ts'],
    restoreMocks: true,
    // A production NODE_ENV in the caller's shell must not swap React for its production build.
    env: { NODE_ENV: 'test' },
  },
})
