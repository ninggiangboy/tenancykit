import { defineConfig } from '@hey-api/openapi-ts'

export default defineConfig({
  input: '../../../contracts/openapi/platform.yaml',
  output: { path: 'src/generated/platform' },
  plugins: [
    '@hey-api/client-fetch',
    '@hey-api/typescript',
    '@hey-api/sdk',
    { name: '@tanstack/react-query', infiniteQueryOptions: false },
    'zod',
  ],
})
