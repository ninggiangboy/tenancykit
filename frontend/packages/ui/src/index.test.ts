import { describe, expect, it } from 'vitest'
import { cn } from './index'

describe('cn', () => {
  it('drops a conflicting utility instead of concatenating it', () => {
    expect(cn('p-2', 'p-4')).toBe('p-4')
  })
})
