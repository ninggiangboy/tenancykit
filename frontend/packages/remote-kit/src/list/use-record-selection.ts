import type { UrlState } from '@suite/ui'

/**
 * The record the URL opens (`?open=<id>`) among the rows on screen, with its neighbours, so a sheet can step through
 * the page without closing.
 */
export function useRecordSelection<Row>(state: UrlState, rows: Row[], keyOf: (row: Row) => string | undefined) {
  const openId = state.get('open')
  const index = openId ? rows.findIndex((row) => keyOf(row) === openId) : -1
  const selected = index >= 0 ? rows[index] : undefined
  const keyAt = (at: number) => {
    const row = rows[at]
    return row ? (keyOf(row) ?? null) : null
  }
  return {
    openId,
    selected,
    open: (id: string) => state.set({ open: id }),
    close: () => state.set({ open: null }),
    prev: index > 0 ? () => state.set({ open: keyAt(index - 1) }) : undefined,
    next: index >= 0 && index < rows.length - 1 ? () => state.set({ open: keyAt(index + 1) }) : undefined,
  }
}
