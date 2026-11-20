import type { ChargeResponse } from '@suite/api-client/billing'
import {
  DetailRow,
  DetailRows,
  Maybe,
  RecordSheet,
  ResourcePage,
  Section,
  useListState,
  useRecordSelection,
} from '@suite/remote-kit'
import {
  Alert,
  Button,
  CopyId,
  type DataTableColumn,
  Money,
  PermissionGate,
  StatusBadge,
  type UrlState,
} from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet } from '../../components/facets'
import { useT } from '../../i18n'
import { CreateChargeDialog } from './components/create-charge-dialog'
import { useCanCreateCharges, useChargeList } from './hooks'

const keyOf = (row: ChargeResponse) => row.id ?? ''

/** The net amount, with the gross struck through when a coupon took something off. */
function ChargeAmount({ charge }: { charge: ChargeResponse }) {
  const discounted = charge.grossAmount && charge.grossAmount.amount !== charge.amount?.amount
  return (
    <span className="flex flex-col items-end">
      <Money minor={charge.amount?.amount ?? 0} currency={charge.amount?.currency} />
      {discounted && (
        <Money
          minor={charge.grossAmount?.amount ?? 0}
          currency={charge.grossAmount?.currency}
          className="text-caption text-muted-foreground line-through"
        />
      )}
    </span>
  )
}

export function ChargesPage({ state }: { state: UrlState }) {
  const t = useT('charges')
  const tc = useT('common')
  const spec = { sortable: ['created_at', 'id'], facets: [useEnumFacet('status', 'status', ['SUCCEEDED', 'FAILED'])] }
  const list = useListState(state, spec)
  const query = useChargeList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const canCreate = useCanCreateCharges()
  const [creating, setCreating] = useState(false)
  const selected = record.selected

  const columns: DataTableColumn<ChargeResponse>[] = [
    {
      key: 'amount',
      header: tc('fields.amount'),
      align: 'end',
      sortable: false,
      mobile: 'primary',
      cardText: (row) => <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />,
      render: (row) => <ChargeAmount charge={row} />,
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'reference',
      header: tc('fields.reference'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => (
        <span className="font-mono text-code">
          <Maybe value={row.reference} />
        </span>
      ),
    },
  ]
  const create = (
    <PermissionGate allowed={canCreate}>
      <Button icon="plus" onClick={() => setCreating(true)}>
        {t('create')}
      </Button>
    </PermissionGate>
  )

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      primaryAction={create}
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
      emptyAction={create}
    >
      {selected && (
        <RecordSheet
          open
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
          description={<StatusBadge status={selected.status} />}
        >
          <div className="flex flex-col gap-5">
            {selected.failureReason && (
              <Alert tone="danger" title={t('failureReason')}>
                {selected.failureReason}
              </Alert>
            )}
            <DetailRows>
              {selected.grossAmount && (
                <DetailRow label={t('gross')}>
                  <Money minor={selected.grossAmount.amount ?? 0} currency={selected.grossAmount.currency} />
                </DetailRow>
              )}
              <DetailRow label={tc('fields.reference')}>
                <Maybe value={selected.reference} />
              </DetailRow>
              <DetailRow label={t('usageRef')}>
                <Maybe value={selected.usageRef} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <CopyId value={selected.id ?? ''} />
              </DetailRow>
            </DetailRows>
            {selected.discounts && selected.discounts.length > 0 && (
              <Section title={t('discounts')}>
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {selected.discounts.map((discount) => (
                    <li key={discount.redemptionId} className="flex justify-between gap-2 px-3 py-2 text-small">
                      <span>{discount.couponName ?? discount.couponId}</span>
                      <Money minor={-(discount.amount?.amount ?? 0)} currency={discount.amount?.currency} />
                    </li>
                  ))}
                </ul>
              </Section>
            )}
          </div>
        </RecordSheet>
      )}
      <CreateChargeDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
