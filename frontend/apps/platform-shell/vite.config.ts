import { shellConfig } from '@suite/build-config'
import { defineConfig } from 'vite'

export default defineConfig(shellConfig({ name: 'platform-shell', port: 5173 }))
