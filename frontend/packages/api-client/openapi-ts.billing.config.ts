import { defineConfig } from '@hey-api/openapi-ts'

export default defineConfig({
  input: '../../../contracts/openapi/billing.yaml',
  output: { path: 'src/generated/billing' },
  plugins: [
    '@hey-api/client-fetch',
    '@hey-api/typescript',
    '@hey-api/sdk',
    { name: '@tanstack/react-query', infiniteQueryOptions: false },
    'zod',
  ],
})
