import { type FilterOperator, type ListFilter, type ListQueryInput, listQuery } from '@suite/api-client'
import {
  BulkBar,
  Button,
  Card,
  CopyId,
  DataTable,
  type DataTableColumn,
  Dialog,
  DialogImpact,
  EmptyState,
  ErrorState,
  FacetedFilter,
  Field,
  FilterChip,
  Icon,
  Kbd,
  Sheet,
  Tabs,
  useHotkey,
} from '@suite/ui'
import { type ReactNode, useRef, useState } from 'react'
import { useT } from '../i18n'
import { errorMessage, errorStatus, useToast } from './runtime'

export interface PageInfo {
  limit?: number
  offset?: number | null
  total?: number | null
  next_cursor?: string | null
}

export interface ListResult<Row> {
  data?: Row[]
  page?: PageInfo | null
}

export type ListParams = Record<string, string>

export function asListQuery<T>(params: ListParams): T {
  return params as unknown as T
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

export function useListState(state: import('@suite/ui').UrlState, spec: ListSpec) {
  const q = state.get('q') ?? ''
  const sort = state.get('sort') ?? spec.defaultSort ?? ''
  const offset = Number(state.get('offset') ?? '0') || 0
  const limit = spec.pageSize ?? 50
  const viewId = state.get('view')
  const view = spec.views?.find((entry) => entry.id === viewId)

  const filters: ListFilter[] = []
  if (q && spec.searchField) {
    filters.push({ field: spec.searchField, op: spec.searchOp ?? 'contains', value: q })
  }
  for (const facet of spec.facets ?? []) {
    const values = (state.get(`f_${facet.key}`) ?? '').split(',').filter(Boolean)
    if (values.length > 0) {
      filters.push({ field: facet.field, op: facet.op ?? 'in', value: values.join(',') })
    }
  }
  for (const filter of view?.filters ?? []) {
    filters.push(filter)
  }

  const input: ListQueryInput = { filters, limit, offset, pageInfo: 'full' }
  if (sort) input.sort = sort
  const params = listQuery(input)

  return {
    q,
    sort,
    sortField: sort.replace(/^-/, ''),
    sortDirection: (sort.startsWith('-') ? 'desc' : 'asc') as 'asc' | 'desc',
    offset,
    limit,
    viewId,
    params,
    activeFilterCount:
      (q ? 1 : 0) + (spec.facets ?? []).filter((facet) => (state.get(`f_${facet.key}`) ?? '') !== '').length,
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

/** The bulk action every list has: the selected rows' identifiers on the clipboard, one per line. */
function BulkCopyIds({ ids }: { ids: string[] }) {
  const t = useT('common')
  const toast = useToast()
  return (
    <Button
      variant="ghost"
      size="sm"
      icon="copy"
      onClick={() => {
        void navigator.clipboard?.writeText(ids.join('\n'))
        toast({ title: t('actions.copyIds'), tone: 'success' })
      }}
    >
      {t('actions.copyIds')}
    </Button>
  )
}

export interface ResourcePageProps<Row> {
  title: string
  description?: string
  state: import('@suite/ui').UrlState
  spec: ListSpec
  rows: Row[]
  page?: PageInfo | null
  loading?: boolean
  error?: unknown
  refetch?: () => void
  columns: DataTableColumn<Row>[]
  rowKey: string | ((row: Row) => string)
  primary?: ReactNode
  headerExtra?: ReactNode
  /** Actions for the selected rows; the default copies their identifiers. */
  bulkActions?: (keys: string[]) => ReactNode
  emptyTitle?: string
  emptyHint?: string
  caption?: string
}

/** A list screen: the page head, saved views, the one-line toolbar, the table and its paging. */
export function ResourcePage<Row>({
  title,
  description,
  state,
  spec,
  rows,
  page,
  loading,
  error,
  refetch,
  columns,
  rowKey,
  primary,
  headerExtra,
  bulkActions,
  emptyTitle,
  emptyHint,
  caption,
}: ResourcePageProps<Row>) {
  const t = useT('common')
  const list = useListState(state, spec)
  const [selected, setSelected] = useState<string[]>([])
  const searchRef = useRef<HTMLInputElement>(null)
  useHotkey('/', () => searchRef.current?.focus())

  const facets = spec.facets ?? []
  const filtered = list.q !== '' || list.activeFilterCount > 0
  const keyOf = (row: Row) =>
    typeof rowKey === 'function' ? rowKey(row) : String((row as Record<string, unknown>)[rowKey] ?? '')
  const offset = page?.offset ?? list.offset
  const total = page?.total ?? null
  const from = rows.length === 0 ? 0 : offset + 1
  const to = offset + rows.length
  const hasNext = total !== null ? to < total : rows.length >= list.limit

  const sortFor = (key: string) => (key === list.sortField ? list.sortDirection : undefined)

  const columnsWithSort = columns.map((column) => ({
    ...column,
    sortable: column.sortable ?? (spec.sortable ?? []).includes(column.key),
    sort: sortFor(column.key),
  }))

  const header = (
    <header className="flex flex-wrap items-start justify-between gap-3 px-6 py-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-title text-foreground">{title}</h1>
        {description && <p className="max-w-2xl text-small text-muted-foreground">{description}</p>}
      </div>
      <div className="flex items-center gap-2">
        {headerExtra}
        {primary}
      </div>
    </header>
  )

  return (
    <div className="flex flex-col">
      {header}
      <div className="flex flex-col gap-3 px-6 pb-8">
        {spec.views && spec.views.length > 0 && (
          <Tabs
            items={spec.views.map((view) => ({ id: view.id, label: view.label }))}
            value={list.viewId ?? spec.views[0]?.id}
            onChange={list.setView}
            label={title}
          />
        )}
        <div className="flex flex-wrap items-center gap-2">
          {spec.searchField && (
            <div className="relative">
              <Icon
                name="search"
                size={14}
                className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground"
              />
              <input
                ref={searchRef}
                value={list.q}
                onChange={(event) => list.setQ(event.target.value)}
                placeholder={spec.searchPlaceholder ?? t('actions.search')}
                aria-label={spec.searchPlaceholder ?? t('actions.search')}
                className="h-7 w-64 rounded-md border border-input bg-background pl-7 pr-8 text-small shadow-xs focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-halo"
              />
              <Kbd keys={['/']} className="absolute right-1.5 top-1/2 -translate-y-1/2" />
            </div>
          )}
          {facets.map((facet) => (
            <FacetedFilter
              key={facet.key}
              label={facet.label}
              options={facet.options}
              selected={(state.get(`f_${facet.key}`) ?? '').split(',').filter(Boolean)}
              onChange={(values) => list.setFacet(facet.key, values)}
            />
          ))}
          {list.q && (
            <FilterChip
              field={spec.searchField ?? 'q'}
              label={spec.searchPlaceholder ?? t('actions.search')}
              op="like"
              value={list.q}
              display={list.q}
              onRemove={() => list.setQ('')}
            />
          )}
          {facets.map((facet) =>
            (state.get(`f_${facet.key}`) ?? '')
              .split(',')
              .filter(Boolean)
              .map((value) => (
                <FilterChip
                  key={`${facet.key}:${value}`}
                  field={facet.field}
                  label={facet.label}
                  op={chipOp(facet.op)}
                  value={value}
                  display={facet.options.find((option) => option.value === value)?.label ?? value}
                  onRemove={() =>
                    list.setFacet(
                      facet.key,
                      (state.get(`f_${facet.key}`) ?? '').split(',').filter((entry) => entry !== value),
                    )
                  }
                />
              )),
          )}
          {filtered && (
            <Button variant="ghost" size="sm" icon="x" onClick={list.clear}>
              {t('actions.clear')}
            </Button>
          )}
        </div>

        {error ? (
          <ErrorState
            title={t('state.error')}
            description={errorMessage(error)}
            forbidden={errorStatus(error) === 403}
            onRetry={refetch}
          />
        ) : (
          <DataTable
            columns={columnsWithSort}
            rows={rows}
            rowKey={rowKey}
            loading={loading}
            onSort={list.setSort}
            onRowOpen={(row) => state.set({ open: keyOf(row) })}
            selectable
            selected={selected}
            onSelect={setSelected}
            caption={caption}
            empty={
              filtered ? (
                <EmptyState icon="search" title={t('table.noResults')} description={t('table.noResultsHint')} />
              ) : (
                <EmptyState
                  icon="inbox"
                  title={emptyTitle ?? t('table.emptyTitle')}
                  description={emptyHint ?? t('table.emptyHint')}
                />
              )
            }
            footer={
              <div className="flex w-full items-center justify-between">
                <span>
                  {total !== null ? t('table.showing', { from, to, total }) : t('table.showingUnknown', { from, to })}
                </span>
                <div className="flex items-center gap-1">
                  <Button
                    variant="outline"
                    size="sm"
                    icon="chevron-left"
                    disabled={list.offset === 0}
                    onClick={() => list.setOffset(Math.max(0, offset - list.limit))}
                  >
                    {t('actions.previous')}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    iconEnd="chevron-right"
                    disabled={!hasNext}
                    onClick={() => list.setOffset(offset + list.limit)}
                  >
                    {t('actions.next')}
                  </Button>
                </div>
              </div>
            }
          />
        )}
        <BulkBar count={selected.length} onClear={() => setSelected([])}>
          {(bulkActions ?? ((keys) => <BulkCopyIds ids={keys} />))(selected)}
        </BulkBar>
      </div>
    </div>
  )
}

export function PageHeader({
  title,
  description,
  children,
}: {
  title: ReactNode
  description?: ReactNode
  children?: ReactNode
}) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-3 px-6 py-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-title text-foreground">{title}</h1>
        {description && <p className="max-w-2xl text-small text-muted-foreground">{description}</p>}
      </div>
      <div className="flex items-center gap-2">{children}</div>
    </header>
  )
}

export function DetailRows({ children }: { children: ReactNode }) {
  return <dl className="divide-y divide-border rounded-lg border border-border">{children}</dl>
}

export function DetailRow({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-3 px-3 py-2 text-small">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="max-w-[60%] break-words text-right text-body-strong text-foreground">{children}</dd>
    </div>
  )
}

export function IdValue({ value }: { value?: string | null }) {
  const t = useT('common')
  if (!value) return <span className="text-muted-foreground">{t('state.none')}</span>
  return <CopyId value={value} />
}

export function Timestamp({ value }: { value?: string | null }) {
  const t = useT('common')
  if (!value) return <span className="text-muted-foreground">{t('state.none')}</span>
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return <span>{value}</span>
  return <time dateTime={value}>{date.toISOString().replace('T', ' ').slice(0, 16)}</time>
}

export interface RecordSheetProps {
  state: import('@suite/ui').UrlState
  open: boolean
  title: ReactNode
  eyebrow?: ReactNode
  description?: ReactNode
  footer?: ReactNode
  onPrev?: () => void
  onNext?: () => void
  children: ReactNode
}

export function RecordSheet({
  state,
  open,
  title,
  eyebrow,
  description,
  footer,
  onPrev,
  onNext,
  children,
}: RecordSheetProps) {
  return (
    <Sheet
      open={open}
      onClose={() => state.set({ open: null })}
      onInteractOutside={(event) => event.preventDefault()}
      title={title}
      eyebrow={eyebrow}
      description={description}
      footer={footer}
      onPrev={onPrev}
      onNext={onNext}
    >
      {children}
    </Sheet>
  )
}

export interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  impact?: Array<{ label: ReactNode; value: ReactNode }>
  confirmLabel: string
  busy?: boolean
  destructive?: boolean
  /** A code or id the person retypes; Confirm stays disabled until it matches. */
  typed?: { label: string; expected: string; value: string; onChange: (value: string) => void }
  onConfirm: () => void
  children?: ReactNode
}

export function ConfirmDialog({
  open,
  onClose,
  title,
  description,
  impact,
  confirmLabel,
  busy,
  destructive = true,
  typed,
  onConfirm,
  children,
}: ConfirmDialogProps) {
  const t = useT('common')
  const mismatch = typed ? typed.value.trim() !== typed.expected : false
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      role="alertdialog"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>
            {t('actions.cancel')}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            size="sm"
            loading={busy}
            disabled={mismatch}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {impact && impact.length > 0 && <DialogImpact items={impact} />}
        {children}
        {typed && (
          <Field
            label={typed.label}
            value={typed.value}
            onChange={(event) => typed.onChange(event.target.value)}
            autoComplete="off"
          />
        )}
      </div>
    </Dialog>
  )
}

export interface FormDialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  submitLabel: string
  busy?: boolean
  onSubmit: () => void
  children: ReactNode
}

export function FormDialog({
  open,
  onClose,
  title,
  description,
  submitLabel,
  busy,
  onSubmit,
  children,
}: FormDialogProps) {
  const t = useT('common')
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>
            {t('actions.cancel')}
          </Button>
          <Button size="sm" type="submit" form="remote-form" loading={busy}>
            {submitLabel}
          </Button>
        </>
      }
    >
      <form
        id="remote-form"
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit()
        }}
      >
        {children}
      </form>
    </Dialog>
  )
}

export interface SecretDialogProps {
  open: boolean
  onClose: () => void
  secret?: string | null
  title?: string
}

/** A key or signing secret shown exactly once, never put in the query cache, the URL or a log. */
export function SecretDialog({ open, onClose, secret, title }: SecretDialogProps) {
  const t = useT('common')
  const toast = useToast()
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title ?? t('secret.title')}
      description={t('secret.hint')}
      footer={
        <Button size="sm" onClick={onClose}>
          {t('secret.done')}
        </Button>
      }
    >
      <Card flush>
        <div className="p-3">
          <code className="block break-all rounded-md bg-muted p-3 font-mono text-code text-foreground">
            {secret ?? ''}
          </code>
        </div>
      </Card>
      <div className="mt-3 flex justify-end">
        <Button
          variant="outline"
          size="sm"
          icon="copy"
          onClick={() => {
            if (secret) {
              void navigator.clipboard?.writeText(secret)
              toast({ title: t('actions.copy'), tone: 'success' })
            }
          }}
        >
          {t('secret.copy')}
        </Button>
      </div>
    </Dialog>
  )
}

/** For a page the caller may not read: the backend would answer 403, so the screen says so before asking. */
export function Forbidden() {
  const t = useT('common')
  return <ErrorState forbidden title={t('state.forbidden')} description={t('state.errorHint')} />
}

function chipOp(
  op: FilterOperator | undefined,
): 'eq' | 'neq' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'nin' | 'like' | 'is_null' {
  if (!op) return 'eq'
  if (op === 'contains' || op === 'starts_with') return 'like'
  return op
}
