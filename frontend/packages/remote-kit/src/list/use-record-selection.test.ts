import { type SearchRecord, urlState } from '@suite/ui'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useRecordSelection } from './use-record-selection'

const rows = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
const keyOf = (row: { id: string }) => row.id

function selection(search: SearchRecord) {
  return renderHook(
    ({ search }) =>
      useRecordSelection(
        urlState(search, () => {}),
        rows,
        keyOf,
      ),
    {
      initialProps: { search },
    },
  )
}

describe('useRecordSelection', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('keeps the record to draw through the leave and tells the sheet when it is open', () => {
    const { result, rerender } = selection({})
    expect(result.current.isOpen).toBe(false)
    expect(result.current.shown).toBeNull()

    rerender({ search: { open: 'b' } })
    expect(result.current.isOpen).toBe(true)
    expect(result.current.selected).toBe(rows[1])
    expect(result.current.shown).toBe(rows[1])

    rerender({ search: {} })
    expect(result.current.isOpen).toBe(false)
    expect(result.current.selected).toBeUndefined()
    expect(result.current.shown).toBe(rows[1])

    act(() => vi.advanceTimersByTime(200))
    expect(result.current.shown).toBeNull()
  })

  it('shows a re-selected record at once and steps to its neighbours', () => {
    const { result, rerender } = selection({ open: 'a' })
    expect(result.current.prev).toBeUndefined()
    expect(result.current.next).toBeDefined()

    rerender({ search: {} })
    rerender({ search: { open: 'c' } })
    expect(result.current.shown).toBe(rows[2])
    expect(result.current.next).toBeUndefined()
    expect(result.current.prev).toBeDefined()
  })
})
