import { describe, expect, it } from 'vitest'
import { remoteConfig, remoteVersion, shellConfig } from './index.ts'

describe('build presets', () => {
  it('serves a shell under /console/ and declares no remote at build time', () => {
    const config = shellConfig({ name: 'shell' })
    expect(config.base).toBe('/console/')
    expect(config.plugins).toHaveLength(3)
  })

  it('serves a built remote from its immutable version directory', () => {
    expect(remoteConfig({ name: 'billing', version: '1.2.3+abc1234' }).base).toBe(
      '/console/remotes/billing/1.2.3+abc1234/',
    )
  })

  it('keeps a standalone remote at the root', () => {
    expect(remoteConfig({ name: 'billing', version: 'dev' }).base).toBe('/')
  })

  it('reads the version from the environment a deploy step sets', () => {
    process.env.REMOTE_VERSION = '9.9.9+deadbee'
    expect(remoteVersion()).toBe('9.9.9+deadbee')
    delete process.env.REMOTE_VERSION
    expect(remoteVersion()).toBe('dev')
  })
})
