import { describe, expect, it } from 'vitest'
import { baseUrlFor } from './index'

describe('base URLs', () => {
  it('keeps Platform behind its prefix and Billing at the root', () => {
    expect(baseUrlFor('platform')).toBe('/platform')
    expect(baseUrlFor('billing')).toBe('')
  })
})
