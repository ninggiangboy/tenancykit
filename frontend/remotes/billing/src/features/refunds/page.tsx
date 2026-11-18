import { permissions } from '@suite/api-client'
import { refunds1Options } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Money, StatusBadge, type UrlState } from '@suite/ui'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'
import {
  asListQuery,
  DetailRow,
  DetailRows,
  IdValue,
  type ListSpec,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useListState,
} from '../../lib/page'
import { useApi } from '../../lib/runtime'

const spec: ListSpec = {
  sortable: ['amount', 'created_at', 'id'],
  facets: [
    {
      key: 'status',
      label: 'Status',
      field: 'status',
      options: [
        { value: 'PENDING', label: 'Pending' },
        { value: 'SUCCEEDED', label: 'Succeeded' },
        { value: 'FAILED', label: 'Failed' },
      ],
    },
  ],
}

export function RefundsPage({ state }: { state: UrlState }) {
  const t = useT('refunds')
  const tc = useT('common')
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.refunds_1)
  const list = useListState(state, spec)

  const query = useQuery({
    ...refunds1Options({
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

  return (
    <>
      <ResourcePage
        title={t('title')}
        description={t('description')}
        state={state}
        spec={spec}
        rows={rows}
        page={query.data?.page}
        loading={query.isLoading}
        error={query.error}
        refetch={() => void query.refetch()}
        rowKey={(row) => row.id ?? ''}
        caption={t('title')}
        emptyTitle={t('emptyTitle')}
        emptyHint={t('emptyHint')}
        columns={[
          {
            key: 'amount',
            header: tc('fields.amount'),
            render: (row) => <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />,
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          { key: 'payment_id', header: t('payment'), render: (row) => <IdValue value={row.paymentId} /> },
          { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={<Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />}
          description={selected.reason}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
        >
          <DetailRows>
            <DetailRow label={tc('fields.amount')}>
              <Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />
            </DetailRow>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={selected.status} />
            </DetailRow>
            <DetailRow label={t('payment')}>
              <IdValue value={selected.paymentId} />
            </DetailRow>
            <DetailRow label={tc('fields.reason')}>{selected.reason ?? tc('state.none')}</DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={selected.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.updated')}>
              <Timestamp value={selected.completedAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <IdValue value={selected.id} />
            </DetailRow>
          </DetailRows>
        </RecordSheet>
      )}
    </>
  )
}
