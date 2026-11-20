import { describe, expect, it } from 'vitest'
import { cn } from './cn'

describe('cn', () => {
  it('keeps a text colour next to a type-scale size', () => {
    const merged = cn('text-primary-foreground', 'text-body-strong')
    expect(merged).toContain('text-primary-foreground')
    expect(merged).toContain('text-body-strong')
  })

  it('lets a later size win over an earlier one', () => {
    expect(cn('text-small', 'text-caption')).toBe('text-caption')
  })

  it('lets a later colour win over an earlier one', () => {
    expect(cn('text-muted-foreground text-small', 'text-destructive')).toBe('text-small text-destructive')
  })
})
