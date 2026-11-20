import { readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { resources } from './index'

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const catalogues: Record<string, Record<string, unknown>> = resources.en

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(path)
    return /\.tsx?$/.test(entry.name) && !/\.test\./.test(entry.name) ? [path] : []
  })
}

/** A message key a screen passes as a literal must exist in the English catalogue, or the screen renders the key itself. */
describe('message keys used in source', () => {
  const missing: string[] = []

  for (const file of sourceFiles(sourceRoot)) {
    const source = readFileSync(file, 'utf8')
    const label = file.slice(sourceRoot.length + 1)

    for (const [, name, namespace] of source.matchAll(/const (\w+) = useT\('(\w+)'\)/g)) {
      for (const [, key] of source.matchAll(new RegExp(`\\b${name}\\('([^'\`]+)'`, 'g'))) {
        if (!(key && namespace && key in (catalogues[namespace] ?? {}))) missing.push(`${label}: ${namespace}:${key}`)
      }
    }

    if (file.endsWith('routes.tsx')) {
      for (const [, namespace, key] of source.matchAll(/'(\w+):([\w.]+)'/g)) {
        if (!(key && namespace && key in (catalogues[namespace] ?? {}))) missing.push(`${label}: ${namespace}:${key}`)
      }
    }
  }

  it('are all in the English catalogue', () => {
    expect(missing).toEqual([])
  })
})
