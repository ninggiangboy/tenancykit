import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { cn, TYPE_SCALE } from './cn'

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

  it('knows every type-scale size theme.css defines, so a size next to a colour keeps both', () => {
    const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'styles/theme.css'), 'utf8')
    const defined = [...css.matchAll(/^\s*--text-([a-z]+(?:-strong)?): [\d.]+px;/gm)].map((match) => match[1])
    expect(defined.length).toBeGreaterThan(5)
    expect([...TYPE_SCALE].sort()).toEqual([...defined].sort())
    expect(cn('text-caption text-muted-foreground', 'text-figure text-foreground')).toBe('text-figure text-foreground')
  })
})
