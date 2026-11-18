import { shellConfig } from '@suite/build-config'
import { defineConfig } from 'vite'

export default defineConfig(shellConfig({ name: 'tenant-shell', port: 5175 }))
