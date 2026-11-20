import { type ListFilter, type ListQueryInput, listQuery } from '@suite/api-client'
import { listParam, type UrlState } from '@suite/ui'
import type { ListSpec } from './list-spec'

export type ListState = ReturnType<typeof useListState>

/** The list's search, facets, view, sort and page, read from and written to the URL. */
export function useListState(state: UrlState, spec: ListSpec) {
  const q = state.get('q') ?? ''
  const sort = state.get('sort') ?? spec.defaultSort ?? ''
  const offset = Number(state.get('offset') ?? '0') || 0
  const limit = spec.pageSize ?? 50
  const viewId = state.get('view') ?? spec.views?.[0]?.id ?? null
  const view = spec.views?.find((entry) => entry.id === viewId)
  const facetValues = (key: string) => listParam(state.get(`f_${key}`))

  const filters: ListFilter[] = []
  if (q && spec.searchField) {
    filters.push({ field: spec.searchField, op: spec.searchOp ?? 'contains', value: q })
  }
  for (const facet of spec.facets ?? []) {
    const values = facetValues(facet.key)
    if (values.length > 0) {
      filters.push({ field: facet.field, op: facet.op ?? 'in', value: values.join(',') })
    }
  }
  for (const filter of view?.filters ?? []) {
    filters.push(filter)
  }

  const input: ListQueryInput = { filters, limit, offset, pageInfo: 'full' }
  if (sort) input.sort = sort

  const activeFacets = (spec.facets ?? []).filter((facet) => facetValues(facet.key).length > 0).length

  return {
    q,
    sort,
    sortField: sort.replace(/^-/, ''),
    sortDirection: (sort.startsWith('-') ? 'desc' : 'asc') as 'asc' | 'desc',
    offset,
    limit,
    viewId,
    params: listQuery(input),
    filtered: q !== '' || activeFacets > 0,
    facetValues,
    setQ: (value: string) => state.set({ q: value || null, offset: null }),
    setSort: (field: string, direction: 'asc' | 'desc') =>
      state.set({ sort: `${direction === 'desc' ? '-' : ''}${field}`, offset: null }),
    setOffset: (value: number) => state.set({ offset: value === 0 ? null : String(value) }),
    setView: (id: string) => state.set({ view: id, offset: null }),
    setFacet: (key: string, values: string[]) => state.set({ [`f_${key}`]: values.join(',') || null, offset: null }),
    clear: () => {
      const next: Record<string, string | null> = { q: null, offset: null }
      for (const facet of spec.facets ?? []) next[`f_${facet.key}`] = null
      state.set(next)
    },
  }
}
