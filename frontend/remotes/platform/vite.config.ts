import { remoteConfig } from '@suite/build-config'
import { SDK_MAJOR } from '@suite/shell-sdk'
import { defineConfig } from 'vite'

export default defineConfig(remoteConfig({ name: 'platform', sdkMajor: SDK_MAJOR, port: 5174 }))
