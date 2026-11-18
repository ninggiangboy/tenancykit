import { describe, expect, it } from 'vitest'
import { listQuery } from './list-query'

describe('listQuery', () => {
  it('builds the documented open-invoices example', () => {
    expect(
      listQuery({
        filters: [
          { field: 'customer_id', op: 'eq', value: '0193' },
          { field: 'status', op: 'eq', value: 'OPEN' },
        ],
        or: [
          [{ field: 'total', op: 'gte', value: 100000 }],
          [{ field: 'created_at', op: 'gte', value: '2026-11-01T00:00:00Z' }],
        ],
        sort: '-total',
        limit: 50,
        pageInfo: 'full',
      }),
    ).toEqual({
      'filter[customer_id][eq]': '0193',
      'filter[status][eq]': 'OPEN',
      'filter[or][0][total][gte]': '100000',
      'filter[or][1][created_at][gte]': '2026-11-01T00:00:00Z',
      sort: '-total',
      limit: '50',
      page_info: 'full',
    })
  })

  it('turns sort terms into the contract syntax', () => {
    expect(listQuery({ sort: [{ field: 'created_at', desc: true }, { field: 'total' }] }).sort).toBe(
      '-created_at,total',
    )
  })

  it('refuses a cursor and an offset together', () => {
    expect(() => listQuery({ cursor: 'abc', offset: 10 })).toThrow(/cursor or an offset/)
  })

  it('clamps the limits the contract enforces', () => {
    expect(listQuery({ limit: 1000 })).toEqual({ limit: '200' })
    expect(listQuery({ offset: 99_999 })).toEqual({ offset: '10000' })
  })

  it('omits paging facts unless asked', () => {
    expect(listQuery({ pageInfo: 'none' })).toEqual({})
    expect(listQuery({ pageInfo: 'cursor' })).toEqual({ page_info: 'cursor' })
  })

  it('refuses more predicates than the contract accepts', () => {
    const filters = Array.from({ length: 21 }, () => ({ field: 'status', op: 'eq' as const, value: 'OPEN' }))
    expect(() => listQuery({ filters })).toThrow(/20 predicates/)
  })
})
