#!/usr/bin/env node
// Turns design/tokens.json into src/styles/tokens.css: one CSS variable per token, light in :root and dark in .dark.
// The names are shadcn's, so a component's class reads the same as the shadcn one it was ported from.
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const tokens = JSON.parse(readFileSync(resolve(here, '../design/tokens.json'), 'utf8'))

const literals = new Map()
for (const token of tokens.color.tokens) {
  if (typeof token.value === 'string') {
    literals.set(token.name, token.value)
  }
}

/** `{foreground}` means "whatever foreground is in this theme". */
const tokenValue = (value, theme) => {
  if (typeof value === 'string') {
    const reference = value.match(/^\{(.+)\}$/)
    return reference ? literalOf(reference[1], theme) : value
  }
  return value[theme]
}

const literalOf = (name, theme) => {
  if (literals.has(name)) {
    return literalOf(literals.get(name), theme)
  }
  const token = tokens.color.tokens.find((entry) => entry.name === name)
  if (!token) throw new Error(`tokens.json refers to ${name}, which it never defines`)
  return tokenValue(token.value, theme)
}

const declarations = (theme) =>
  tokens.color.tokens.map((token) => `  --${token.name}: ${tokenValue(token.value, theme)};`).join('\n')

const radii = tokens.radius.tokens.map((token) => `  --radius-${token.name.replace('radius-', '')}: ${token.value};`)
const sizes = tokens.size.tokens.map((token) => `  --size-${token.name}: ${token.value};`)
const durations = tokens.timing.tokens.map((token) => `  --${token.name}: ${token.value};`)

const shadows = (theme) =>
  tokens.shadow.tokens.map((token) => `  --shadow-${token.name.replace('shadow-', '')}: ${token.value[theme]};`)

const css = `/* Generated from design/tokens.json by pnpm --filter @suite/ui tokens:build. Do not edit by hand. */

:root {
${declarations('light')}
${shadows('light').join('\n')}
${radii.join('\n')}
${sizes.join('\n')}
${durations.join('\n')}
  --font-sans: ${tokens.type.families.sans};
  --font-mono: ${tokens.type.families.mono};
}

.dark {
${declarations('dark')}
${shadows('dark').join('\n')}
}
`

writeFileSync(resolve(here, '../src/styles/tokens.css'), css)
console.log(`wrote ${tokens.color.tokens.length} colour tokens for light and dark`)
