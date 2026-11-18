/**
 * One list endpoint, one request shape: the flat keys of the list-query contract. A caller describes filters, sort and
 * position; this builds exactly the query string the API documents, so no screen writes `filter[` by hand.
 */

export type FilterOperator =
  | 'eq'
  | 'neq'
  | 'in'
  | 'nin'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'is_null'
  | 'starts_with'
  | 'contains'

export interface ListFilter {
  field: string
  op: FilterOperator
  value: string | number | boolean
}

export interface SortTerm {
  field: string
  desc?: boolean
}

export interface ListQueryInput {
  /** Predicates combined with AND. */
  filters?: ListFilter[]
  /** Branches combined with OR; predicates inside one branch are AND-ed. */
  or?: ListFilter[][]
  /** A field list such as `-created_at,total`, or terms. */
  sort?: string | SortTerm[]
  limit?: number
  /** A cursor and an offset are mutually exclusive; the contract refuses both. */
  cursor?: string
  offset?: number
  /** `full` asks for every paging fact, `cursor` for the cursor alone, `none` (the default) for rows alone. */
  pageInfo?: 'none' | 'cursor' | 'full'
}

/** The contract's own limits; a client that exceeds them would only earn a 400. */
const MAX_PREDICATES = 20
const MAX_OR_BRANCHES = 10
const MAX_LIMIT = 200
const MAX_OFFSET = 10_000

export function listQuery(input: ListQueryInput = {}): Record<string, string> {
  const query: Record<string, string> = {}
  const filters = input.filters ?? []
  const branches = input.or ?? []

  if (input.cursor !== undefined && input.offset !== undefined) {
    throw new Error('a list query takes a cursor or an offset, never both')
  }
  if (filters.length + branches.flat().length > MAX_PREDICATES) {
    throw new Error(`a list query holds at most ${MAX_PREDICATES} predicates`)
  }
  if (branches.length > MAX_OR_BRANCHES) {
    throw new Error(`a list query holds at most ${MAX_OR_BRANCHES} OR branches`)
  }

  for (const filter of filters) {
    query[`filter[${filter.field}][${filter.op}]`] = text(filter.value)
  }
  branches.forEach((branch, index) => {
    for (const filter of branch) {
      query[`filter[or][${index}][${filter.field}][${filter.op}]`] = text(filter.value)
    }
  })

  if (input.sort !== undefined) {
    query.sort = typeof input.sort === 'string' ? input.sort : sortTerms(input.sort)
  }
  if (input.limit !== undefined) {
    query.limit = String(Math.min(input.limit, MAX_LIMIT))
  }
  if (input.cursor !== undefined) {
    query.cursor = input.cursor
  }
  if (input.offset !== undefined) {
    query.offset = String(Math.min(input.offset, MAX_OFFSET))
  }
  if (input.pageInfo && input.pageInfo !== 'none') {
    query.page_info = input.pageInfo
  }
  return query
}

function sortTerms(terms: SortTerm[]): string {
  return terms.map((term) => (term.desc ? `-${term.field}` : term.field)).join(',')
}

function text(value: string | number | boolean): string {
  return String(value)
}
