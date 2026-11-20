import { BulkBar, DataTable, type DataTableColumn, EmptyState, Tabs } from '@suite/ui'
import { type ReactNode, useState } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'
import { ErrorPanel } from '../layout/error-panel'
import { ListPager } from './list-pager'
import type { ListSpec, PageInfo } from './list-spec'
import { ListToolbar } from './list-toolbar'
import type { ListState } from './use-list-state'

export interface ResourceListProps<Row> {
  /** Names the table and the view tabs for a screen reader. */
  label: string
  spec: ListSpec
  list: ListState
  rows: Row[]
  page?: PageInfo | null
  loading?: boolean
  error?: unknown
  refetch?: () => void
  columns: DataTableColumn<Row>[]
  rowKey: (row: Row) => string
  onRowOpen?: (row: Row) => void
  /** Actions for the selected rows. A list without them shows no selection at all. */
  bulkActions?: (keys: string[]) => ReactNode
  /** Each saved view's total, as `useViewCounts` returns it. */
  viewCounts?: Record<string, number | undefined>
  emptyTitle?: string
  emptyHint?: string
  /** A call to action inside the empty state, usually the page's create button. */
  emptyAction?: ReactNode
}

/** Saved views, the toolbar, the table, its paging and the bulk bar: everything of a list but the page header. */
export function ResourceList<Row>({
  label,
  spec,
  list,
  rows,
  page,
  loading,
  error,
  refetch,
  columns,
  rowKey,
  onRowOpen,
  bulkActions,
  viewCounts,
  emptyTitle,
  emptyHint,
  emptyAction,
}: ResourceListProps<Row>) {
  const t = useKitT()
  const [selected, setSelected] = useState<string[]>([])
  const sortable = new Set(spec.sortable ?? [])
  const sortedColumns = columns.map((column) => ({
    ...column,
    sortable: column.sortable ?? sortable.has(column.key),
    sort: column.key === list.sortField ? list.sortDirection : undefined,
  }))

  return (
    <div className="flex flex-col gap-3">
      {spec.views && spec.views.length > 0 && (
        <Tabs
          items={spec.views.map((view) => ({ id: view.id, label: view.label, count: viewCounts?.[view.id] }))}
          value={list.viewId ?? spec.views[0]?.id}
          onChange={list.setView}
          label={label}
          variant="tiles"
        />
      )}
      <ListToolbar spec={spec} list={list} />
      {error ? (
        <ErrorPanel error={error} onRetry={refetch} />
      ) : (
        <DataTable
          columns={sortedColumns}
          rows={rows}
          rowKey={rowKey}
          loading={loading}
          onSort={list.setSort}
          onRowOpen={onRowOpen}
          selectable={Boolean(bulkActions)}
          selected={selected}
          onSelect={setSelected}
          caption={label}
          empty={
            list.filtered ? (
              <EmptyState icon="search" title={t('table.noResults')} description={t('table.noResultsHint')} />
            ) : (
              <EmptyState
                icon="inbox"
                title={emptyTitle ?? t('table.emptyTitle')}
                description={emptyHint ?? t('table.emptyHint')}
              >
                {emptyAction}
              </EmptyState>
            )
          }
          footer={
            rows.length > 0 || list.offset > 0 ? <ListPager list={list} page={page} count={rows.length} /> : undefined
          }
        />
      )}
      {bulkActions && (
        <BulkBar count={selected.length} onClear={() => setSelected([])}>
          {bulkActions(selected)}
        </BulkBar>
      )}
    </div>
  )
}
