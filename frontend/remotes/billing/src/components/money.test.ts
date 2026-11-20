import { describe, expect, it } from 'vitest'
import { fromMinor, toMinor } from './money'

describe('money', () => {
  it('converts by the currency’s own minor unit', () => {
    expect(toMinor('10.50', 'USD')).toBe(1050)
    expect(toMinor('25000', 'VND')).toBe(25000)
    expect(fromMinor(1050, 'USD')).toBe('10.50')
    expect(fromMinor(25000, 'VND')).toBe('25000')
  })
})
