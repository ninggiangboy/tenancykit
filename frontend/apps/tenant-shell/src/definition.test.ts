import { describe, expect, it } from 'vitest'
import { definition } from './definition'

describe('the tenant shell', () => {
  it('serves the tenant audience', () => {
    expect(definition.audience).toBe('TENANT')
    expect(definition.title).toBe('Console')
  })
})
