import type { FacetSpec } from '@suite/remote-kit'
import { useT } from '../i18n'

/** A labelled facet over a fixed set of enum values; each label is `common:enum.<VALUE>`. */
export function useEnumFacet(key: string, field: string, values: readonly string[], label?: string): FacetSpec {
  const tc = useT('common')
  return {
    key,
    field,
    label: label ?? tc(`fields.${key}`),
    options: values.map((value) => ({ value, label: tc(`enum.${value}`) })),
  }
}

/** The label of one enum value, for a select or a badge. */
export function useEnumLabel(): (value: string) => string {
  const tc = useT('common')
  return (value) => tc(`enum.${value}`)
}
