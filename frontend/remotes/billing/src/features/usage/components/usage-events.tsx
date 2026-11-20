import type { UsageEventResponse } from '@suite/api-client/billing'
import {
  DetailRow,
  DetailRows,
  Maybe,
  RecordSheet,
  ResourceList,
  Timestamp,
  useListState,
  useRecordSelection,
} from '@suite/remote-kit'
import { CopyId, type DataTableColumn, type UrlState } from '@suite/ui'
import { CustomerCell, useCustomerNames } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useUsageHistory } from '../hooks'

const keyOf = (row: UsageEventResponse) => row.id ?? ''

export function UsageEvents({ state }: { state: UrlState }) {
  const t = useT('usage')
  const tc = useT('common')
  const spec = {
    sortable: ['occurred_at', 'id'],
    searchField: 'metric',
    searchOp: 'contains' as const,
    searchPlaceholder: t('search'),
  }
  const list = useListState(state, spec)
  const query = useUsageHistory(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const names = useCustomerNames(rows.map((row) => row.customerId))
  const selected = record.selected

  const columns: DataTableColumn<UsageEventResponse>[] = [
    {
      key: 'metric',
      header: t('metric'),
      sortable: false,
      mobile: 'primary',
      render: (row) => <span className="font-mono text-code text-foreground">{row.metric}</span>,
    },
    {
      key: 'quantity',
      header: t('quantity'),
      align: 'end',
      sortable: false,
      mobile: 'secondary',
      render: (row) => (
        <span className="tabular-nums">
          {row.quantity} <span className="text-muted-foreground">{row.unit}</span>
        </span>
      ),
    },
    {
      key: 'customer',
      header: tc('fields.customer'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => <CustomerCell id={row.customerId} names={names} />,
    },
    {
      key: 'occurred_at',
      header: t('occurredAt'),
      mobile: 'meta',
      cardText: (row) => row.occurredAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.occurredAt} />,
    },
  ]

  return (
    <>
      <ResourceList
        label={t('events')}
        spec={spec}
        list={list}
        rows={rows}
        page={query.data?.page}
        loading={query.isLoading}
        error={query.error}
        refetch={() => void query.refetch()}
        columns={columns}
        rowKey={keyOf}
        onRowOpen={(row) => record.open(keyOf(row))}
        emptyTitle={t('emptyTitle')}
        emptyHint={t('emptyHint')}
      />
      {selected && (
        <RecordSheet
          open
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
          eyebrow={t('events')}
          title={<span className="font-mono">{selected.metric}</span>}
          description={`${selected.quantity ?? ''} ${selected.unit ?? ''}`}
        >
          <DetailRows>
            <DetailRow label={tc('fields.customer')}>
              <CustomerCell id={selected.customerId} names={names} />
            </DetailRow>
            <DetailRow label={t('occurredAt')}>
              <Timestamp value={selected.occurredAt} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={selected.recordedAt} />
            </DetailRow>
            <DetailRow label={t('resourceRef')}>
              <Maybe value={selected.resourceRef} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={selected.id ?? ''} />
            </DetailRow>
          </DetailRows>
        </RecordSheet>
      )}
    </>
  )
}
