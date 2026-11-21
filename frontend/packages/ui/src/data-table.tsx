// biome-ignore-all lint/a11y/noStaticElementInteractions: a server-driven table puts its handlers on the wrapper and rows.
// biome-ignore-all lint/a11y/useKeyWithClickEvents: j/k, Enter and x are handled once, on the table's wrapper.
// biome-ignore-all lint/a11y/noNoninteractiveTabindex: the card list is the table: a card takes focus so Enter opens it.
// biome-ignore-all lint/a11y/useAriaPropsSupportedByRole: a card carries aria-selected like the row it replaces.
import { Fragment, type KeyboardEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Checkbox } from './checkbox'
import { cn } from './cn'
import { EmptyState } from './empty-state'
import { Icon } from './icon'
import { useUiMessages } from './messages'
import { Skeleton } from './skeleton'
import { PHONE_QUERY, useMedia } from './use-media'

export type MobileRole = 'primary' | 'badge' | 'secondary' | 'meta' | 'hidden'

export interface DataTableColumn<Row> {
  key: string
  header: React.ReactNode
  align?: 'start' | 'end'
  mono?: boolean
  width?: string | number
  sortable?: boolean
  sort?: 'asc' | 'desc'
  render?: (row: Row) => React.ReactNode
  /** Where the column goes in the phone card list; `hidden` also leaves the table below 1024px. */
  mobile: MobileRole
  /** Plain text for the card when `render` returns a two-line cell. */
  cardText?: (row: Row) => React.ReactNode
  /** Shown only in the card list, for a value the table already shows as the second line of another cell (a code, an email). */
  cardOnly?: boolean
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
  /** `auto` is the table from 768px up and the card list below; `table` and `cards` force one. */
  layout?: 'auto' | 'table' | 'cards'
  /** Shows the selection control in the card list before any row is selected. */
  selecting?: boolean
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
  layout = 'auto',
  selecting,
  loading,
  empty,
  footer,
  caption,
  className,
}: DataTableProps<Row>) {
  const t = useUiMessages()
  const phone = useMedia(PHONE_QUERY)
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
  const cards = layout === 'cards' || (layout === 'auto' && phone)
  const cardRows = cards && !loading && rows.length > 0
  // On a phone an empty list has no columns to name: only the empty state.
  const bare = cards && !loading && rows.length === 0
  const tableColumns = columns.filter((column) => !column.cardOnly)

  const select = useCallback(
    (keys: string[]) => {
      if (selected === undefined) {
        setInternalSelected(keys)
      }
      onSelect?.(keys)
    },
    [selected, onSelect],
  )
  const toggleRow = useCallback(
    (key: string) => {
      select(selection.includes(key) ? selection.filter((entry) => entry !== key) : [...selection, key])
    },
    [selection, select],
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
        toggleRow(active)
      }
    },
    [rows, active, selectable, onRowOpen, keyOf, toggleRow, move],
  )

  useEffect(() => {
    const listener = (event: globalThis.KeyboardEvent) => onKeyDown(event)
    document.addEventListener('keydown', listener)
    return () => document.removeEventListener('keydown', listener)
  }, [onKeyDown])

  return (
    <div className={cn('flex flex-col', className)}>
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        {cardRows ? (
          <CardList
            rows={rows}
            columns={columns}
            keyOf={keyOf}
            caption={caption}
            selectable={selectable}
            selection={selection}
            selecting={selecting}
            onToggle={toggleRow}
            onActivate={activate}
            onRowOpen={onRowOpen}
          />
        ) : bare ? null : (
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
                {tableColumns.map((column) => (
                  <th
                    key={column.key}
                    scope="col"
                    style={{ width: column.width }}
                    aria-sort={column.sort === 'asc' ? 'ascending' : column.sort === 'desc' ? 'descending' : undefined}
                    className={cn(
                      'h-10 px-3 text-[12.5px] font-medium text-muted-foreground',
                      column.align === 'end' ? 'text-right' : 'text-left',
                      column.mobile === 'hidden' && 'max-lg:hidden',
                    )}
                  >
                    {column.sortable && onSort ? (
                      <button
                        type="button"
                        onClick={() => onSort(column.key, column.sort === 'asc' ? 'desc' : 'asc')}
                        className="motion-colors -mx-1.5 inline-flex h-7 items-center gap-1 rounded-md px-1.5 hover:bg-accent hover:text-foreground max-md:h-(--size-touch-target)"
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
                    {tableColumns.map((column) => (
                      <td key={column.key} className={cn('px-3 py-2', column.mobile === 'hidden' && 'max-lg:hidden')}>
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
                        'motion-colors group border-b border-border last:border-0',
                        isActive
                          ? 'bg-selection shadow-[inset_2px_0_0_var(--primary)]'
                          : isSelected
                            ? 'bg-selection'
                            : 'hover:bg-accent/60',
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
                            onCheckedChange={() => toggleRow(key)}
                          />
                        </td>
                      )}
                      {tableColumns.map((column) => (
                        <td
                          key={column.key}
                          className={cn(
                            'px-3',
                            density === 'compact' ? 'h-(--size-row-height-compact)' : 'h-(--size-row-height)',
                            column.align === 'end' ? 'text-right' : 'text-left',
                            column.mono && 'font-mono text-code',
                            column.mobile === 'hidden' && 'max-lg:hidden',
                          )}
                        >
                          {column.render
                            ? column.render(row)
                            : String((row as Record<string, unknown>)[column.key] ?? '')}
                        </td>
                      ))}
                      {rowActions && (
                        <td className="px-2 text-right opacity-0 group-hover:opacity-100 group-data-[state=active]:opacity-100 focus-within:opacity-100 [@media(hover:none)]:opacity-100">
                          {rowActions(row)}
                        </td>
                      )}
                    </tr>
                  )
                })}
            </tbody>
          </table>
        )}
        {/* Inside the frame, under the header, so the columns stay named and the empty state is part of the list. */}
        {!loading && rows.length === 0 && (empty ?? <EmptyState title={t('table.empty')} />)}
      </div>
      {footer && (
        <div className="flex items-center justify-between px-1 py-2 text-small text-muted-foreground">{footer}</div>
      )}
    </div>
  )
}

interface CardListProps<Row> {
  rows: Row[]
  columns: DataTableColumn<Row>[]
  keyOf: (row: Row) => string
  caption?: string
  selectable?: boolean
  selection: string[]
  selecting?: boolean
  onToggle: (key: string) => void
  onActivate: (key: string) => void
  onRowOpen?: (row: Row) => void
}

/** The phone form of the table: one card a record, with the columns a screen said belong there. */
function CardList<Row>({
  rows,
  columns,
  keyOf,
  caption,
  selectable,
  selection,
  selecting,
  onToggle,
  onActivate,
  onRowOpen,
}: CardListProps<Row>) {
  const primary = columns.find((column) => column.mobile === 'primary') ?? columns[0]
  const badge = columns.find((column) => column.mobile === 'badge')
  const secondary = columns.filter((column) => column.mobile === 'secondary')
  const meta = columns.find((column) => column.mobile === 'meta')
  const showCheck = selectable && (selecting || selection.length > 0)
  const text = (column: DataTableColumn<Row> | undefined, row: Row): React.ReactNode =>
    column === undefined ? null : (column.cardText?.(row) ?? column.render?.(row) ?? rowValue(row, column.key))

  return (
    <ul aria-label={caption}>
      {rows.map((row) => {
        const key = keyOf(row)
        const isSelected = selection.includes(key)
        const secondaries = secondary
          .map((column) => ({ column, node: text(column, row) }))
          .filter(({ node }) => Boolean(node))
        const open = () => {
          onActivate(key)
          onRowOpen?.(row)
        }
        return (
          <li
            key={key}
            tabIndex={0}
            aria-selected={selectable ? isSelected : undefined}
            className={cn(
              'grid min-h-16 cursor-pointer items-center gap-x-3 gap-y-0.5 border-b border-border px-4 py-2.5 last:border-0 active:bg-accent motion-colors',
              showCheck ? 'grid-cols-[auto_minmax(0,1fr)_auto]' : 'grid-cols-[minmax(0,1fr)_auto]',
            )}
            onClick={(event) => {
              if ((event.target as HTMLElement).closest('button, a, input, label')) {
                return
              }
              if (showCheck) {
                onToggle(key)
                return
              }
              open()
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                open()
              }
            }}
          >
            {showCheck && (
              <span className="row-span-2 mr-1" onClick={(event) => event.stopPropagation()}>
                <Checkbox aria-label={key} checked={isSelected} onCheckedChange={() => onToggle(key)} />
              </span>
            )}
            <span className="truncate text-[15px] font-semibold">{text(primary, row)}</span>
            <span className="justify-self-end">{text(badge, row)}</span>
            <span className="truncate text-small text-muted-foreground">
              {secondaries.map(({ column, node }, index) => (
                <Fragment key={column.key}>
                  {index > 0 && ' · '}
                  {node}
                </Fragment>
              ))}
            </span>
            <span className="justify-self-end text-small text-muted-foreground tabular-nums">{text(meta, row)}</span>
          </li>
        )
      })}
    </ul>
  )
}

function rowValue<Row>(row: Row, key: string): React.ReactNode {
  return (row as Record<string, React.ReactNode>)[key]
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
