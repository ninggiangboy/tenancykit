import { describe, expect, it } from 'vitest'
import { permissions } from './index'

describe('the permission map', () => {
  it('is generated for both documents', () => {
    expect(Object.keys(permissions.platform).length).toBeGreaterThan(20)
    expect(Object.keys(permissions.billing).length).toBeGreaterThan(20)
  })

  it('names the permission an operation enforces', () => {
    expect(permissions.platform.registerVersion).toBe('platform:ui-remote:register')
    expect(permissions.platform.setState).toBe('platform:ui-remote:manage')
    expect(Object.keys(permissions.platform)).toContain('setState')
  })
})
