import { permissions } from '@suite/api-client'
import type { RefundResponse } from '@suite/api-client/billing'
import { refunds1Options } from '@suite/api-client/billing/query'
import {
  asListQuery,
  DetailRow,
  DetailRows,
  Maybe,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useApi,
  useListState,
  useRecordSelection,
} from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { CopyId, type DataTableColumn, Money, StatusBadge, type UrlState } from '@suite/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEnumFacet } from '../../components/facets'
import { useT } from '../../i18n'

const keyOf = (row: RefundResponse) => row.id ?? ''

/** Read-only: a refund is requested from its payment, so this screen lists and explains them. */
export function RefundsPage({ state }: { state: UrlState }) {
  const t = useT('refunds')
  const tc = useT('common')
  const { billing } = useApi()
  const spec = {
    sortable: ['amount', 'created_at', 'id'],
    facets: [useEnumFacet('status', 'status', ['PENDING', 'SUCCEEDED', 'FAILED'])],
  }
  const list = useListState(state, spec)
  const query = useQuery({
    ...refunds1Options({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.billing.refunds_1),
  })
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const selected = record.shown

  const columns: DataTableColumn<RefundResponse>[] = [
    {
      key: 'amount',
      header: tc('fields.amount'),
      align: 'end',
      mobile: 'primary',
      cardText: (row) => <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />,
      render: (row) => (
        <span className="font-semibold tabular-nums">
          <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'reason',
      header: tc('fields.reason'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => <Maybe value={row.reason} />,
    },
    {
      key: 'payment_id',
      header: t('payment'),
      mobile: 'hidden',
      render: (row) => <CopyId value={row.paymentId ?? ''} />,
    },
    {
      key: 'created_at',
      header: tc('fields.created'),
      mobile: 'meta',
      cardText: (row) => row.createdAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.createdAt} />,
    },
  ]

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      spec={spec}
      list={list}
      rows={rows}
      page={query.data?.page}
      loading={query.isLoading}
      refreshing={query.isFetching && !query.isLoading}
      error={query.error}
      refetch={() => void query.refetch()}
      columns={columns}
      rowKey={keyOf}
      onRowOpen={(row) => record.open(keyOf(row))}
      emptyTitle={t('emptyTitle')}
      emptyHint={t('emptyHint')}
    >
      {selected && (
        <RecordSheet
          open={record.isOpen}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
          eyebrow={
            <>
              {t('recordType')} · <CopyId value={selected.id ?? ''} />
            </>
          }
          title={<Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />}
          figure
          description={
            <>
              <StatusBadge status={selected.status} />
              <Timestamp value={selected.createdAt} />
            </>
          }
        >
          <DetailRows>
            <DetailRow label={t('payment')}>
              <CopyId value={selected.paymentId ?? ''} />
            </DetailRow>
            <DetailRow label={tc('fields.reason')}>
              <Maybe value={selected.reason} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={selected.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.updated')}>
              <Timestamp value={selected.completedAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={selected.id ?? ''} />
            </DetailRow>
          </DetailRows>
        </RecordSheet>
      )}
    </ResourcePage>
  )
}
