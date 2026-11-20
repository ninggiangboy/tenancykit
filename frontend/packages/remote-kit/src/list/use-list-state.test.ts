import { type SearchRecord, urlState } from '@suite/ui'
import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useListState } from './use-list-state'

function stateOf(search: SearchRecord) {
  const writes: SearchRecord[] = []
  return { state: urlState(search, (next) => writes.push(next)), writes }
}

describe('useListState', () => {
  it('turns the search, a facet and a view into one list query', () => {
    const { state } = stateOf({ q: 'acme', f_status: 'ACTIVE,PAUSED', view: 'open', sort: '-created_at' })
    const { result } = renderHook(() =>
      useListState(state, {
        searchField: 'name',
        facets: [{ key: 'status', label: 'Status', field: 'status', options: [] }],
        views: [{ id: 'open', label: 'Open', filters: [{ field: 'state', op: 'eq', value: 'OPEN' }] }],
      }),
    )
    expect(result.current.params).toMatchObject({
      'filter[name][contains]': 'acme',
      'filter[status][in]': 'ACTIVE,PAUSED',
      'filter[state][eq]': 'OPEN',
      sort: '-created_at',
    })
    expect(result.current.filtered).toBe(true)
    expect(result.current.sortDirection).toBe('desc')
  })

  it('resets the page when a filter changes', () => {
    const { state, writes } = stateOf({ offset: '50' })
    const { result } = renderHook(() => useListState(state, { searchField: 'name' }))
    result.current.setQ('x')
    expect(writes.at(-1)).toEqual({ q: 'x' })
  })

  it('starts on the first saved view', () => {
    const { state } = stateOf({})
    const { result } = renderHook(() => useListState(state, { views: [{ id: 'all', label: 'All' }] }))
    expect(result.current.viewId).toBe('all')
  })
})
