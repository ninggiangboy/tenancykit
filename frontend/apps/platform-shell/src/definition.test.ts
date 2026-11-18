import { describe, expect, it } from 'vitest'
import { definition } from './definition'

describe('the platform shell', () => {
  it('serves the platform audience', () => {
    expect(definition.audience).toBe('PLATFORM')
    expect(definition.title).toBe('Platform')
  })
})
