import type { WalletResponse } from '@suite/api-client/billing'
import {
  DetailRow,
  DetailRows,
  IdValue,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useListState,
  useRecordSelection,
} from '@suite/remote-kit'
import { Button, type DataTableColumn, Money, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet, useEnumLabel } from '../../components/facets'
import { CustomerCell, useCustomerNames } from '../../components/lookups'
import { CURRENCIES, WALLET_MODES } from '../../components/options'
import { useT } from '../../i18n'
import { Ledger } from './components/ledger'
import { OpenWalletDialog } from './components/open-wallet-dialog'
import { useWalletList, useWalletPermissions } from './hooks'

const keyOf = (row: WalletResponse) => row.id ?? ''

function Amount({ value, currency }: { value?: { amount?: number; currency?: string }; currency?: string }) {
  return <Money minor={value?.amount ?? 0} currency={value?.currency ?? currency} />
}

export function WalletsPage({ state }: { state: UrlState }) {
  const t = useT('wallets')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const spec = {
    sortable: ['created_at', 'id'],
    facets: [
      // A currency code is its own label in every language.
      {
        key: 'currency',
        field: 'currency',
        label: tc('fields.currency'),
        options: CURRENCIES.map((value) => ({ value, label: value })),
      },
      useEnumFacet('mode', 'mode', WALLET_MODES),
    ],
  }
  const list = useListState(state, spec)
  const query = useWalletList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const names = useCustomerNames(rows.map((row) => row.customerId))
  const can = useWalletPermissions()
  const [opening, setOpening] = useState(false)
  const selected = record.selected

  const columns: DataTableColumn<WalletResponse>[] = [
    {
      key: 'customer_id',
      header: tc('fields.customer'),
      sortable: false,
      render: (row) => <CustomerCell id={row.customerId} names={names} />,
    },
    {
      key: 'balance',
      header: tc('fields.balance'),
      align: 'end',
      render: (row) => <Amount value={row.balance} currency={row.currency} />,
    },
    {
      key: 'available',
      header: tc('fields.available'),
      align: 'end',
      sortable: false,
      render: (row) => <Amount value={row.available} currency={row.currency} />,
    },
    {
      key: 'mode',
      header: tc('fields.mode'),
      render: (row) => <StatusBadge tone="neutral">{row.mode ? enumLabel(row.mode) : ''}</StatusBadge>,
    },
    { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
  ]
  const create = (
    <PermissionGate allowed={can.open}>
      <Button icon="plus" onClick={() => setOpening(true)}>
        {t('create')}
      </Button>
    </PermissionGate>
  )

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      actions={create}
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
          eyebrow={`${t('title')} · ${selected.currency ?? ''} ${selected.mode ? enumLabel(selected.mode) : ''}`}
          title={<Amount value={selected.balance} currency={selected.currency} />}
          description={<CustomerCell id={selected.customerId} names={names} />}
        >
          <div className="flex flex-col gap-5">
            <DetailRows>
              <DetailRow label={tc('fields.held')}>
                <Amount value={selected.held} currency={selected.currency} />
              </DetailRow>
              <DetailRow label={tc('fields.available')}>
                <Amount value={selected.available} currency={selected.currency} />
              </DetailRow>
              {selected.mode === 'POSTPAID' && (
                <DetailRow label={tc('fields.creditLimit')}>
                  <Amount value={selected.creditLimit} currency={selected.currency} />
                </DetailRow>
              )}
              <DetailRow label={tc('fields.created')}>
                <Timestamp value={selected.createdAt} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>
            <Ledger wallet={selected} canReverse={can.reverse} />
          </div>
        </RecordSheet>
      )}
      <OpenWalletDialog open={opening} onClose={() => setOpening(false)} />
    </ResourcePage>
  )
}
