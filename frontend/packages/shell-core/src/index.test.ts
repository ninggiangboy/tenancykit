import { describe, expect, it } from 'vitest'
import { PLATFORM_SHELL, TENANT_SHELL } from './index'

describe('shell definitions', () => {
  it('splits the two audiences', () => {
    expect(PLATFORM_SHELL.audience).toBe('PLATFORM')
    expect(TENANT_SHELL.audience).toBe('TENANT')
  })
})
