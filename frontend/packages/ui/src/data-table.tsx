// biome-ignore-all lint/a11y/noStaticElementInteractions: a server-driven table puts its handlers on the wrapper and rows.
// biome-ignore-all lint/a11y/useKeyWithClickEvents: j/k, Enter and x are handled once, on the table's wrapper.
import { type KeyboardEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Checkbox } from './checkbox'
import { cn } from './cn'
import { EmptyState } from './empty-state'
import { Icon } from './icon'
import { useUiMessages } from './messages'
import { Skeleton } from './skeleton'

export interface DataTableColumn<Row> {
  key: string
  header: React.ReactNode
  align?: 'start' | 'end'
  mono?: boolean
  width?: string | number
  sortable?: boolean
  sort?: 'asc' | 'desc'
  render?: (row: Row) => React.ReactNode
}

export interface DataTableProps<Row> {
  columns: DataTableColumn<Row>[]
  rows: Row[]
  rowKey?: string | ((row: Row) => string)
  selectable?: boolean
  selected?: string[]
  defaultSelected?: string[]
  onSelect?: (keys: string[]) => void
  activeKey?: string
  defaultActiveKey?: string
  onActiveChange?: (key: string) => void
  /** Click or Enter on a row: open the record in a Sheet. */
  onRowOpen?: (row: Row) => void
  rowActions?: (row: Row) => React.ReactNode
  onSort?: (key: string, direction: 'asc' | 'desc') => void
  density?: 'comfortable' | 'compact'
  loading?: boolean
  empty?: React.ReactNode
  footer?: React.ReactNode
  caption?: string
  className?: string
}

/**
 * Records of one kind. Keyboard: j/k or the arrows move, Enter opens, x selects; nothing is trapped, so the page's own
 * shortcuts still work while the table has focus.
 */
export function DataTable<Row>({
  columns,
  rows,
  rowKey = 'id',
  selectable,
  selected,
  defaultSelected = [],
  onSelect,
  activeKey,
  defaultActiveKey,
  onActiveChange,
  onRowOpen,
  rowActions,
  onSort,
  density = 'comfortable',
  loading,
  empty,
  footer,
  caption,
  className,
}: DataTableProps<Row>) {
  const t = useUiMessages()
  const keyOf = useCallback(
    (row: Row) => (typeof rowKey === 'function' ? rowKey(row) : String((row as Record<string, unknown>)[rowKey])),
    [rowKey],
  )
  const [internalSelected, setInternalSelected] = useState<string[]>(defaultSelected)
  const [internalActive, setInternalActive] = useState<string | undefined>(defaultActiveKey)
  const selection = selected ?? internalSelected
  const active = activeKey ?? internalActive
  const bodyRef = useRef<HTMLTableSectionElement>(null)
  const showSkeleton = useDelayed(loading === true, 150)

  const select = useCallback(
    (keys: string[]) => {
      if (selected === undefined) {
        setInternalSelected(keys)
      }
      onSelect?.(keys)
    },
    [selected, onSelect],
  )
  const activate = useCallback(
    (key: string | undefined) => {
      if (activeKey === undefined) {
        setInternalActive(key)
      }
      onActiveChange?.(key ?? '')
    },
    [activeKey, onActiveChange],
  )
  const move = useCallback(
    (delta: number) => {
      if (rows.length === 0) return
      const current = rows.findIndex((row) => keyOf(row) === active)
      const next = Math.min(Math.max(current + delta, 0), rows.length - 1)
      activate(keyOf(rows[next] as Row))
    },
    [rows, active, keyOf, activate],
  )

  /**
   * j/k, Enter and x work while the list is on screen, not only after the table itself took focus: the design system
   * treats the list as the page. Field, menu and dialog targets are left alone, and a table inside a page with another
   * table only answers for its own rows.
   */
  const onKeyDown = useCallback(
    (event: KeyboardEvent | globalThis.KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable], [role="menu"], [role="dialog"]')) {
        return
      }
      const table = target?.closest('table')
      const own = bodyRef.current?.closest('table')
      if (table && own && own !== table) {
        return
      }
      const key = event.key.toLowerCase()
      if (key === 'j' || key === 'arrowdown') {
        event.preventDefault()
        move(1)
      } else if (key === 'k' || key === 'arrowup') {
        event.preventDefault()
        move(-1)
      } else if (key === 'enter' && active) {
        const row = rows.find((entry) => keyOf(entry) === active)
        if (row) {
          event.preventDefault()
          onRowOpen?.(row)
        }
      } else if (key === 'x' && active && selectable) {
        event.preventDefault()
        select(selection.includes(active) ? selection.filter((entry) => entry !== active) : [...selection, active])
      }
    },
    [rows, active, selection, selectable, onRowOpen, keyOf, select, move],
  )

  useEffect(() => {
    const listener = (event: globalThis.KeyboardEvent) => onKeyDown(event)
    document.addEventListener('keydown', listener)
    return () => document.removeEventListener('keydown', listener)
  }, [onKeyDown])

  return (
    <div className={cn('flex flex-col', className)}>
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table className="w-full border-collapse text-body" aria-busy={loading || undefined}>
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead>
            <tr className="border-b border-border text-left">
              {selectable && (
                <th scope="col" className="w-9 px-3 py-2">
                  <Checkbox
                    aria-label={t('table.selectAll')}
                    checked={rows.length > 0 && selection.length === rows.length}
                    indeterminate={selection.length > 0 && selection.length < rows.length}
                    onCheckedChange={(checked) => select(checked ? rows.map(keyOf) : [])}
                  />
                </th>
              )}
              {columns.map((column) => (
                <th
                  key={column.key}
                  scope="col"
                  style={{ width: column.width }}
                  aria-sort={column.sort === 'asc' ? 'ascending' : column.sort === 'desc' ? 'descending' : undefined}
                  className={cn(
                    'px-3 py-2 text-caption font-medium text-muted-foreground',
                    column.align === 'end' ? 'text-right' : 'text-left',
                  )}
                >
                  {column.sortable && onSort ? (
                    <button
                      type="button"
                      onClick={() => onSort(column.key, column.sort === 'asc' ? 'desc' : 'asc')}
                      className="inline-flex items-center gap-1 hover:text-foreground"
                      aria-label={t('table.sort', { column: String(column.header) })}
                    >
                      {column.header}
                      <Icon
                        name={
                          column.sort === 'asc' ? 'arrow-up' : column.sort === 'desc' ? 'arrow-down' : 'arrow-up-down'
                        }
                        size={12}
                      />
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              ))}
              {rowActions && <th scope="col" className="w-10" aria-label="Actions" />}
            </tr>
          </thead>
          <tbody ref={bodyRef}>
            {showSkeleton &&
              SKELETON_ROWS.map((row) => (
                <tr key={row} className="border-b border-border last:border-0">
                  {selectable && (
                    <td className="px-3 py-2">
                      <Skeleton width={16} height={16} />
                    </td>
                  )}
                  {columns.map((column) => (
                    <td key={column.key} className="px-3 py-2">
                      <Skeleton width="70%" />
                    </td>
                  ))}
                  {rowActions && <td />}
                </tr>
              ))}
            {!loading &&
              rows.map((row) => {
                const key = keyOf(row)
                const isActive = key === active
                const isSelected = selection.includes(key)
                return (
                  <tr
                    key={key}
                    data-state={isActive ? 'active' : isSelected ? 'selected' : undefined}
                    aria-selected={selectable ? isSelected : undefined}
                    className={cn(
                      'group border-b border-border last:border-0 transition-colors duration-[120ms]',
                      isActive ? 'bg-accent' : isSelected ? 'bg-selection' : 'hover:bg-accent/60',
                      onRowOpen && 'cursor-pointer',
                    )}
                    onClick={(event) => {
                      if ((event.target as HTMLElement).closest('button, a, input, [role="menu"]')) {
                        return
                      }
                      activate(key)
                      onRowOpen?.(row)
                    }}
                  >
                    {selectable && (
                      <td className="px-3" onClick={(event) => event.stopPropagation()}>
                        <Checkbox
                          aria-label={t('table.selectRow')}
                          checked={isSelected}
                          onCheckedChange={() =>
                            select(isSelected ? selection.filter((entry) => entry !== key) : [...selection, key])
                          }
                        />
                      </td>
                    )}
                    {columns.map((column) => (
                      <td
                        key={column.key}
                        className={cn(
                          'px-3',
                          density === 'compact' ? 'h-9' : 'h-11',
                          column.align === 'end' ? 'text-right' : 'text-left',
                          column.mono && 'font-mono text-code',
                        )}
                      >
                        {column.render
                          ? column.render(row)
                          : String((row as Record<string, unknown>)[column.key] ?? '')}
                      </td>
                    ))}
                    {rowActions && (
                      <td className="px-2 text-right opacity-0 group-hover:opacity-100 group-data-[state=active]:opacity-100 focus-within:opacity-100">
                        {rowActions(row)}
                      </td>
                    )}
                  </tr>
                )
              })}
          </tbody>
        </table>
      </div>
      {!loading && rows.length === 0 && (empty ?? <EmptyState title={t('table.empty')} />)}
      {footer && (
        <div className="flex items-center justify-between px-1 py-2 text-small text-muted-foreground">{footer}</div>
      )}
    </div>
  )
}

/** The five placeholder rows a loading table shows. */
const SKELETON_ROWS = ['a', 'b', 'c', 'd', 'e'] as const

/** Shows a loading state only after the delay, so a fast answer never flashes a skeleton. */
function useDelayed(value: boolean, delay: number): boolean {
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    if (!value) {
      setVisible(false)
      return
    }
    const timer = setTimeout(() => setVisible(true), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return visible
}
