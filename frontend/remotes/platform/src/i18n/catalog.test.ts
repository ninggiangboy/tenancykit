import { describe, expect, it } from 'vitest'
import { resources } from './index'

function keys(value: Record<string, unknown>): string[] {
  return Object.keys(value).sort()
}

describe('i18n catalogs', () => {
  it('every namespace holds the same keys in English and Vietnamese', () => {
    for (const namespace of Object.keys(resources.en) as Array<keyof typeof resources.en>) {
      expect(keys(resources.vi[namespace]), `namespace ${namespace}`).toEqual(keys(resources.en[namespace]))
    }
  })

  it('has no empty message', () => {
    for (const locale of ['en', 'vi'] as const) {
      for (const [namespace, catalog] of Object.entries(resources[locale])) {
        for (const [key, value] of Object.entries(catalog)) {
          expect(value, `${locale}/${namespace}:${key}`).not.toBe('')
        }
      }
    }
  })
})
