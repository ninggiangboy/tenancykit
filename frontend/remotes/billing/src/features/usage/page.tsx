import { permissions } from '@suite/api-client'
import type { BatchResponse, TotalResponse, UsageEventRequest } from '@suite/api-client/billing'
import { aggregates, recordBatch, recordOne } from '@suite/api-client/billing'
import { historyOptions } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Alert, Button, Card, DataTable, Field, PermissionGate, Tabs, type UrlState } from '@suite/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, Tooltip as ChartTooltip, ResponsiveContainer, XAxis, YAxis } from 'recharts'
import { useT } from '../../i18n'
import {
  asListQuery,
  DetailRow,
  DetailRows,
  FormDialog,
  IdValue,
  type ListSpec,
  PageHeader,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useListState,
} from '../../lib/page'
import { errorMessage, useAction, useApi, useToast } from '../../lib/runtime'

const spec: ListSpec = {
  sortable: ['occurred_at', 'id'],
  searchField: 'metric',
  searchOp: 'contains',
  searchPlaceholder: 'Search by metric',
  facets: [],
}

export function UsagePage({ state }: { state: UrlState }) {
  const t = useT('usage')
  const [view, setView] = useState<'events' | 'aggregates'>(
    state.get('view') === 'aggregates' ? 'aggregates' : 'events',
  )
  return (
    <div className="flex flex-col">
      <PageHeader title={t('title')} description={t('description')} />
      <div className="flex flex-col gap-3 px-6 pb-8">
        <Tabs
          items={[
            { id: 'events', label: t('events') },
            { id: 'aggregates', label: t('aggregates') },
          ]}
          value={view}
          onChange={(id) => {
            const next = id as 'events' | 'aggregates'
            setView(next)
            state.set({ view: next })
          }}
          label={t('title')}
        />
        {view === 'events' ? <Events state={state} /> : <Aggregates />}
      </div>
    </div>
  )
}

function Events({ state }: { state: UrlState }) {
  const t = useT('usage')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.history)
  const canRecord = usePermission(permissions.billing.recordOne)
  const list = useListState(state, spec)
  const query = useQuery({
    ...historyOptions({
      client: billing,
      throwOnError: true,
      query: asListQuery<Record<string, string>>(list.params),
    }),
    enabled: canRead,
  })
  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [oneOpen, setOneOpen] = useState(false)
  const [batchOpen, setBatchOpen] = useState(false)
  const [form, setForm] = useState({
    customerId: '',
    metric: '',
    quantity: '',
    unit: '',
    occurredAt: '',
    resourceRef: '',
  })
  const [json, setJson] = useState('')
  const [batchError, setBatchError] = useState('')
  const [batchResult, setBatchResult] = useState<BatchResponse | null>(null)

  const record = useAction(
    (vars: typeof form) =>
      recordOne({
        client: billing,
        throwOnError: true,
        body: {
          customerId: vars.customerId.trim(),
          metric: vars.metric.trim(),
          quantity: vars.quantity.trim(),
          unit: vars.unit.trim() || undefined,
          occurredAt: vars.occurredAt ? new Date(vars.occurredAt).toISOString() : undefined,
          resourceRef: vars.resourceRef.trim() || undefined,
        },
      }),
    {
      onSuccess: () => {
        setOneOpen(false)
        toast({ title: t('recorded'), tone: 'success' })
      },
    },
  )

  const batch = useMutation({
    mutationFn: async (events: UsageEventRequest[]) =>
      (await recordBatch({ client: billing, throwOnError: true, body: { events } })).data,
    onSuccess: (result) => {
      setBatchResult(result)
      toast({ title: t('batchRecorded'), tone: 'success' })
    },
    onError: (error) => setBatchError(errorMessage(error)),
  })

  return (
    <>
      <ResourcePage
        title={t('events')}
        description={t('description')}
        state={state}
        spec={spec}
        rows={rows}
        page={query.data?.page}
        loading={query.isLoading}
        error={query.error}
        refetch={() => void query.refetch()}
        rowKey={(row) => row.id ?? ''}
        caption={t('events')}
        emptyTitle={t('emptyTitle')}
        emptyHint={t('emptyHint')}
        primary={
          <PermissionGate allowed={canRecord}>
            <Button variant="outline" icon="send" onClick={() => setBatchOpen(true)}>
              {t('batch')}
            </Button>
            <Button icon="plus" onClick={() => setOneOpen(true)}>
              {t('record')}
            </Button>
          </PermissionGate>
        }
        columns={[
          {
            key: 'metric',
            header: t('metric'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.metric}</span>
                <span className="text-caption text-muted-foreground">{row.customerId}</span>
              </span>
            ),
          },
          {
            key: 'quantity',
            header: t('quantity'),
            render: (row) => (
              <span className="font-mono text-code">
                {row.quantity} {row.unit}
              </span>
            ),
          },
          { key: 'occurred_at', header: t('occurredAt'), render: (row) => <Timestamp value={row.occurredAt} /> },
          {
            key: 'id',
            header: tc('fields.id'),
            sortable: false,
            render: (row) => <IdValue value={row.id} />,
          },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('events')}
          title={selected.metric ?? ''}
          description={selected.customerId}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
        >
          <DetailRows>
            <DetailRow label={t('metric')}>{selected.metric}</DetailRow>
            <DetailRow label={t('quantity')}>
              {selected.quantity} {selected.unit}
            </DetailRow>
            <DetailRow label={t('customerId')}>
              <IdValue value={selected.customerId} />
            </DetailRow>
            <DetailRow label={t('occurredAt')}>
              <Timestamp value={selected.occurredAt} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={selected.recordedAt} />
            </DetailRow>
            <DetailRow label={t('resourceRef')}>{selected.resourceRef ?? tc('state.none')}</DetailRow>
            <DetailRow label={tc('fields.id')}>
              <IdValue value={selected.id} />
            </DetailRow>
          </DetailRows>
        </RecordSheet>
      )}

      <FormDialog
        open={oneOpen}
        onClose={() => setOneOpen(false)}
        title={t('recordTitle')}
        description={t('recordHint')}
        submitLabel={t('record')}
        busy={record.isPending}
        onSubmit={() => record.mutate(form)}
      >
        <Field
          label={t('customerId')}
          value={form.customerId}
          onChange={(event) => setForm({ ...form, customerId: event.target.value })}
          mono
          required
        />
        <Field
          label={t('metric')}
          value={form.metric}
          onChange={(event) => setForm({ ...form, metric: event.target.value })}
          mono
          required
        />
        <Field
          label={t('quantity')}
          value={form.quantity}
          onChange={(event) => setForm({ ...form, quantity: event.target.value })}
          mono
          required
        />
        <Field
          label={t('unit')}
          value={form.unit}
          onChange={(event) => setForm({ ...form, unit: event.target.value })}
          optional
        />
        <Field
          label={t('occurredAt')}
          type="datetime-local"
          value={form.occurredAt}
          onChange={(event) => setForm({ ...form, occurredAt: event.target.value })}
          optional
        />
        <Field
          label={t('resourceRef')}
          value={form.resourceRef}
          onChange={(event) => setForm({ ...form, resourceRef: event.target.value })}
          optional
        />
      </FormDialog>

      <FormDialog
        open={batchOpen}
        onClose={() => {
          setBatchOpen(false)
          setBatchResult(null)
          setBatchError('')
        }}
        title={t('batchTitle')}
        description={t('batchHint')}
        submitLabel={t('batch')}
        busy={batch.isPending}
        onSubmit={() => {
          setBatchError('')
          let events: UsageEventRequest[]
          try {
            const parsed = JSON.parse(json) as unknown
            if (!Array.isArray(parsed)) {
              setBatchError(t('json'))
              return
            }
            events = parsed as UsageEventRequest[]
          } catch (error) {
            setBatchError(errorMessage(error))
            return
          }
          batch.mutate(events)
        }}
      >
        <Field
          label={t('json')}
          type="textarea"
          mono
          value={json}
          onChange={(event) => setJson(event.target.value)}
          hint={t('batchHint')}
        />
        {batchError && (
          <Alert tone="danger" title={tc('state.error')}>
            {batchError}
          </Alert>
        )}
        {batchResult && (
          <div className="flex flex-col gap-1">
            <h3 className="text-body-strong text-foreground">{t('results')}</h3>
            <ul className="divide-y divide-border rounded-lg border border-border">
              {(batchResult.results ?? []).map((result, index) => (
                <li key={`${index}`} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                  <span className="font-mono text-code">{result.event?.id ?? '—'}</span>
                  <span>{result.replayed ? t('results') : result.event?.metric}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </FormDialog>
    </>
  )
}

function Aggregates() {
  const t = useT('usage')
  const tc = useT('common')
  const { billing } = useApi()
  const [form, setForm] = useState({ customerId: '', metric: '', from: '', to: '' })
  const query = useQuery({
    queryKey: ['usage-aggregates', form],
    queryFn: async () =>
      (
        await aggregates({
          client: billing,
          throwOnError: true,
          query: {
            customerId: form.customerId.trim(),
            from: form.from ? new Date(form.from).toISOString() : '',
            to: form.to ? new Date(form.to).toISOString() : '',
            metric: form.metric ? [form.metric.trim()] : undefined,
          },
        })
      ).data,
    enabled: false,
  })

  const totals = query.data ?? []
  return (
    <div className="flex flex-col gap-3">
      <Card title={t('aggregates')} description={t('description')}>
        <div className="grid grid-cols-2 gap-3">
          <Field
            label={t('customerId')}
            value={form.customerId}
            onChange={(event) => setForm({ ...form, customerId: event.target.value })}
            mono
            required
          />
          <Field
            label={t('metric')}
            value={form.metric}
            onChange={(event) => setForm({ ...form, metric: event.target.value })}
            mono
            optional
          />
          <Field
            label={t('from')}
            type="datetime-local"
            value={form.from}
            onChange={(event) => setForm({ ...form, from: event.target.value })}
          />
          <Field
            label={t('to')}
            type="datetime-local"
            value={form.to}
            onChange={(event) => setForm({ ...form, to: event.target.value })}
          />
        </div>
        <div className="mt-3 flex justify-end">
          <Button
            loading={query.isFetching}
            disabled={!form.customerId || !form.from || !form.to}
            onClick={() => void query.refetch()}
          >
            {t('aggregates')}
          </Button>
        </div>
        {query.error && (
          <Alert tone="danger" title={tc('state.error')}>
            {errorMessage(query.error)}
          </Alert>
        )}
      </Card>
      {totals.length > 0 && <AggregateChart totals={totals} />}
    </div>
  )
}

function AggregateChart({ totals }: { totals: TotalResponse[] }) {
  const t = useT('usage')
  const data = totals.map((total) => ({
    metric: total.metric ?? '',
    quantity: Number(total.quantity ?? 0),
    events: total.events ?? 0,
    unit: total.unit ?? '',
  }))
  return (
    <Card title={t('chart')}>
      <div style={{ height: 260 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="metric" fontSize={12} />
            <YAxis fontSize={12} />
            <ChartTooltip />
            <Bar dataKey="quantity" fill="var(--chart-1)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <Separator />
      <h3 className="mt-3 text-body-strong text-foreground">{t('table')}</h3>
      <DataTable
        columns={[
          { key: 'metric', header: t('metric') },
          { key: 'quantity', header: t('quantity'), align: 'end' },
          { key: 'unit', header: t('unit') },
          { key: 'events', header: t('events'), align: 'end' },
        ]}
        rows={data}
        rowKey={(row) => row.metric}
        caption={t('table')}
      />
    </Card>
  )
}

function Separator() {
  return <hr className="mt-3 border-border" />
}
