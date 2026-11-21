import { describe, expect, it } from 'vitest'
import { shortenIds } from './short-id'

describe('shortenIds', () => {
  it('keeps the first eight characters of a UUID and marks the cut', () => {
    expect(shortenIds('01a1fc39-9ecf-7ef5-98aa-839c4d1e2f3a')).toBe('01a1fc39…')
  })

  it('shortens a UUID inside a reference and leaves the rest of the text', () => {
    expect(shortenIds('subscription:01a1fc39-9ecf-7ef5-98aa-839c4d1e2f3a (renewal)')).toBe(
      'subscription:01a1fc39… (renewal)',
    )
  })

  it('leaves text without a UUID, and an empty value, alone', () => {
    expect(shortenIds('INV-000001')).toBe('INV-000001')
    expect(shortenIds(undefined)).toBe('')
  })
})
