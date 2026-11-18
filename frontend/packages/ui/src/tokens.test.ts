import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const tokens = JSON.parse(readFileSync(resolve(here, '../design/tokens.json'), 'utf8')) as {
  color: { tokens: Array<{ name: string; value: unknown }> }
}
const css = readFileSync(resolve(here, 'styles/tokens.css'), 'utf8')
const block = (selector: string) => css.slice(css.indexOf(selector), css.indexOf('}', css.indexOf(selector)))

/** `{foreground}` means "the value of that token in this theme". */
function resolveValue(value: unknown, theme: 'light' | 'dark'): string {
  if (typeof value === 'string') {
    const reference = value.match(/^\{(.+)\}$/)
    if (!reference) return value
    const target = tokens.color.tokens.find((token) => token.name === reference[1])
    if (!target) throw new Error(`unknown reference ${reference[1]}`)
    return resolveValue(target.value, theme)
  }
  return (value as Record<'light' | 'dark', string>)[theme]
}

describe('generated tokens', () => {
  it('writes every colour token for light and dark', () => {
    const light = block(':root')
    const dark = block('.dark')
    for (const token of tokens.color.tokens) {
      expect(light, `${token.name} in :root`).toContain(`--${token.name}: ${resolveValue(token.value, 'light')};`)
      expect(dark, `${token.name} in .dark`).toContain(`--${token.name}: ${resolveValue(token.value, 'dark')};`)
    }
  })

  it('keeps the shadcn names the components read', () => {
    for (const name of [
      'background',
      'foreground',
      'card',
      'popover',
      'muted',
      'accent',
      'border',
      'input',
      'ring',
      'primary',
    ]) {
      expect(css).toContain(`--${name}:`)
    }
  })

  it('writes the radius, shadow, size and timing scales', () => {
    expect(css).toContain('--radius-lg: 8px;')
    expect(css).toContain('--shadow-md:')
    expect(css).toContain('--size-control-md: 32px;')
    expect(css).toContain('--duration-base: 180ms;')
  })
})
