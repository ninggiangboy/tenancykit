import type { FilterOperator, ListFilter } from '@suite/api-client'

export interface PageInfo {
  limit?: number
  offset?: number | null
  total?: number | null
  next_cursor?: string | null
}

export interface FacetSpec {
  key: string
  label: string
  field: string
  op?: FilterOperator
  options: Array<{ value: string; label: string }>
}

export interface ViewSpec {
  id: string
  label: string
  filters?: ListFilter[]
}

/** What one list screen lets a person do: search one field, filter facets, switch saved views, sort and page. */
export interface ListSpec {
  searchField?: string
  searchOp?: FilterOperator
  searchPlaceholder?: string
  facets?: FacetSpec[]
  views?: ViewSpec[]
  defaultSort?: string
  /** Fields the listing declares sortable; a column outside this set never sends a sort. */
  sortable?: string[]
  pageSize?: number
}

/** The generated query types are per operation; the list-query helper produces the same flat record for all of them. */
export function asListQuery<T = Record<string, string>>(params: Record<string, string>): T {
  return params as unknown as T
}
