import type { RedemptionResponse } from '@suite/api-client/billing'
import {
  ConfirmDialog,
  DetailRow,
  DetailRows,
  ErrorPanel,
  IdValue,
  Page,
  RecordSheet,
  Timestamp,
  useRecordSelection,
} from '@suite/remote-kit'
import {
  Button,
  Combobox,
  DataTable,
  type DataTableColumn,
  EmptyState,
  Money,
  PermissionGate,
  StatusBadge,
  type UrlState,
} from '@suite/ui'
import { useState } from 'react'
import { useWalletOptions } from '../../components/lookups'
import { useT } from '../../i18n'
import { AttachCouponDialog, RedeemCodeDialog } from './components/redemption-dialogs'
import { useRedemptionPermissions, useRevokeRedemption, useWalletRedemptions } from './hooks'

const keyOf = (row: RedemptionResponse) => row.id ?? ''

/** Redemptions belong to a wallet, so the screen starts by picking one; the choice lives in the URL. */
export function RedemptionsPage({ state }: { state: UrlState }) {
  const t = useT('redemptions')
  const tc = useT('common')
  const walletId = state.get('wallet') ?? ''
  const [search, setSearch] = useState('')
  const wallets = useWalletOptions(search)
  const query = useWalletRedemptions(walletId)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const can = useRedemptionPermissions()
  const [dialog, setDialog] = useState<'redeem' | 'attach' | 'revoke' | null>(null)
  const revoke = useRevokeRedemption(() => setDialog(null))
  const selected = record.selected
  const uses = (row: RedemptionResponse) => `${row.applicationsUsed ?? 0}/${row.maxApplications ?? t('unlimited')}`

  const columns: DataTableColumn<RedemptionResponse>[] = [
    {
      key: 'coupon',
      header: t('coupon'),
      render: (row) => <span className="text-body-strong text-foreground">{row.couponName}</span>,
    },
    { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'applications',
      header: t('applications'),
      render: (row) => <span className="tabular-nums">{uses(row)}</span>,
    },
    {
      key: 'discount_total',
      header: t('discountTotal'),
      align: 'end',
      render: (row) => <Money minor={row.discountTotal ?? 0} currency={row.currency} />,
    },
    { key: 'redeemed_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.redeemedAt} /> },
  ]

  return (
    <Page
      title={t('title')}
      description={t('description')}
      actions={
        <PermissionGate allowed={can.redeem && walletId !== ''}>
          <Button variant="outline" icon="ticket" onClick={() => setDialog('redeem')}>
            {t('redeem')}
          </Button>
          <Button icon="plus" onClick={() => setDialog('attach')}>
            {t('attach')}
          </Button>
        </PermissionGate>
      }
    >
      <div className="max-w-md">
        <Combobox
          label={t('walletPicker')}
          value={walletId || null}
          onChange={(value) => state.set({ wallet: value, open: null })}
          options={wallets.options}
          loading={wallets.loading}
          onSearch={setSearch}
        />
      </div>
      {walletId === '' ? (
        <EmptyState icon="wallet" title={t('empty')} />
      ) : query.error ? (
        <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={keyOf}
          loading={query.isLoading}
          onRowOpen={(row) => record.open(keyOf(row))}
          caption={t('title')}
          empty={<EmptyState icon="ticket" title={t('emptyTitle')} description={t('emptyHint')} />}
        />
      )}
      {selected && (
        <RecordSheet
          open
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
          eyebrow={t('title')}
          title={selected.couponName ?? ''}
          description={<StatusBadge status={selected.status} />}
          footer={
            <PermissionGate allowed={can.revoke && selected.status !== 'REVOKED'}>
              <Button variant="destructive" size="sm" icon="ban" onClick={() => setDialog('revoke')}>
                {t('revoke')}
              </Button>
            </PermissionGate>
          }
        >
          <DetailRows>
            <DetailRow label={t('applications')}>{uses(selected)}</DetailRow>
            <DetailRow label={t('discountTotal')}>
              <Money minor={selected.discountTotal ?? 0} currency={selected.currency} />
            </DetailRow>
            <DetailRow label={tc('fields.customer')}>
              <IdValue value={selected.customerId} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={selected.redeemedAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <IdValue value={selected.id} />
            </DetailRow>
          </DetailRows>
        </RecordSheet>
      )}
      <RedeemCodeDialog walletId={walletId} open={dialog === 'redeem'} onClose={() => setDialog(null)} />
      <AttachCouponDialog walletId={walletId} open={dialog === 'attach'} onClose={() => setDialog(null)} />
      <ConfirmDialog
        open={dialog === 'revoke'}
        onClose={() => setDialog(null)}
        title={tc('confirm.revokeRedemption')}
        description={tc('confirm.revokeRedemptionHint')}
        confirmLabel={t('revoke')}
        busy={revoke.isPending}
        onConfirm={() => revoke.mutate(selected?.id ?? '')}
        impact={[
          { label: t('coupon'), value: selected?.couponName ?? '' },
          {
            label: t('discountTotal'),
            value: <Money minor={selected?.discountTotal ?? 0} currency={selected?.currency} />,
          },
        ]}
      />
    </Page>
  )
}
