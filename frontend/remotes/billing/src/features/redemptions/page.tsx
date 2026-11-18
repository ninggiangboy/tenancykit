import { permissions } from '@suite/api-client'
import { attach, redeemByCode, revoke } from '@suite/api-client/billing'
import { redemptionsOfWalletOptions } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, DataTable, Field, Money, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useT } from '../../i18n'
import {
  ConfirmDialog,
  DetailRow,
  DetailRows,
  FormDialog,
  IdValue,
  PageHeader,
  RecordSheet,
  Timestamp,
} from '../../lib/page'
import { useAction, useApi, useToast } from '../../lib/runtime'

export function RedemptionsPage({ state }: { state: UrlState }) {
  const t = useT('redemptions')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.redemptionsOfWallet)
  const canManage = usePermission(permissions.billing.revoke)
  const canRedeem = usePermission(permissions.billing.redeemByCode)
  const [walletId, setWalletId] = useState(state.get('wallet') ?? '')
  const activeWallet = state.get('wallet') ?? ''
  const openId = state.get('open')

  const query = useQuery({
    ...redemptionsOfWalletOptions({
      client: billing,
      throwOnError: true,
      path: { walletId: activeWallet },
    }),
    enabled: canRead && activeWallet !== '',
  })
  const rows = query.data?.data ?? []
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [redeemOpen, setRedeemOpen] = useState(false)
  const [attachOpen, setAttachOpen] = useState(false)
  const [value, setValue] = useState('')
  const [revokeOpen, setRevokeOpen] = useState(false)

  const doRedeem = useAction(
    (vars: { walletId: string; value: string }) =>
      redeemByCode({
        client: billing,
        throwOnError: true,
        path: { walletId: vars.walletId },
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: vars.value.startsWith('coupon:') ? { couponId: vars.value.slice(7) } : { code: vars.value },
      }),
    {
      onSuccess: () => {
        setRedeemOpen(false)
        setValue('')
        toast({ title: t('redeemed'), tone: 'success' })
      },
    },
  )

  const doAttach = useAction(
    (vars: { walletId: string; couponId: string }) =>
      attach({
        client: billing,
        throwOnError: true,
        path: { walletId: vars.walletId },
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: { couponId: vars.couponId },
      }),
    {
      onSuccess: () => {
        setAttachOpen(false)
        setValue('')
        toast({ title: t('attached'), tone: 'success' })
      },
    },
  )

  const doRevoke = useAction(
    (vars: { id: string }) => revoke({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setRevokeOpen(false)
        toast({ title: t('revoked'), tone: 'success' })
      },
    },
  )

  return (
    <div className="flex flex-col">
      <PageHeader title={t('title')} description={t('description')}>
        <PermissionGate allowed={canRedeem && activeWallet !== ''}>
          <Button variant="outline" icon="ticket" onClick={() => setRedeemOpen(true)}>
            {t('redeem')}
          </Button>
        </PermissionGate>
        <PermissionGate allowed={canRedeem && activeWallet !== ''}>
          <Button icon="plus" onClick={() => setAttachOpen(true)}>
            {t('attach')}
          </Button>
        </PermissionGate>
      </PageHeader>
      <div className="flex flex-col gap-3 px-6 pb-8">
        <div className="flex items-end gap-2">
          <Field
            label={t('walletId')}
            value={walletId}
            onChange={(event) => setWalletId(event.target.value)}
            mono
            className="w-96"
          />
          <Button onClick={() => state.set({ wallet: walletId.trim() || null, open: null })}>
            {tc('actions.search')}
          </Button>
        </div>
        {activeWallet === '' ? (
          <p className="text-small text-muted-foreground">{t('empty')}</p>
        ) : (
          <DataTable
            columns={[
              {
                key: 'coupon',
                header: tc('fields.name'),
                render: (row) => (
                  <span className="flex flex-col">
                    <span className="text-body-strong text-foreground">{row.couponName}</span>
                    <span className="font-mono text-caption text-muted-foreground">{row.couponId}</span>
                  </span>
                ),
              },
              { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
              {
                key: 'applications',
                header: t('applications'),
                render: (row) => <span>{`${row.applicationsUsed ?? 0}/${row.maxApplications ?? '∞'}`}</span>,
              },
              {
                key: 'discount_total',
                header: t('discountTotal'),
                render: (row) => <Money minor={row.discountTotal ?? 0} currency={row.currency} />,
              },
              {
                key: 'redeemed_at',
                header: tc('fields.created'),
                render: (row) => <Timestamp value={row.redeemedAt} />,
              },
            ]}
            rows={rows}
            rowKey={(row) => row.id ?? ''}
            loading={query.isLoading}
            onRowOpen={(row) => state.set({ open: row.id ?? null })}
            empty={<p className="p-4 text-small text-muted-foreground">{t('emptyTitle')}</p>}
          />
        )}
      </div>

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={selected.couponName ?? ''}
          description={selected.status}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canManage && selected.status !== 'REVOKED'}>
              <Button variant="destructive" size="sm" icon="ban" onClick={() => setRevokeOpen(true)}>
                {t('revoke')}
              </Button>
            </PermissionGate>
          }
        >
          <DetailRows>
            <DetailRow label={tc('fields.name')}>{selected.couponName}</DetailRow>
            <DetailRow label={tc('fields.wallet')}>
              <IdValue value={selected.walletId} />
            </DetailRow>
            <DetailRow label={tc('fields.customer')}>
              <IdValue value={selected.customerId} />
            </DetailRow>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={selected.status} />
            </DetailRow>
            <DetailRow label={t('applications')}>
              {`${selected.applicationsUsed ?? 0}/${selected.maxApplications ?? '∞'}`}
            </DetailRow>
            <DetailRow label={t('discountTotal')}>
              <Money minor={selected.discountTotal ?? 0} currency={selected.currency} />
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

      <FormDialog
        open={redeemOpen}
        onClose={() => setRedeemOpen(false)}
        title={t('redeem')}
        submitLabel={t('redeem')}
        busy={doRedeem.isPending}
        onSubmit={() => doRedeem.mutate({ walletId: activeWallet, value })}
      >
        <Field
          label={t('redeem')}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          mono
          hint={t('redeemHint')}
        />
      </FormDialog>

      <FormDialog
        open={attachOpen}
        onClose={() => setAttachOpen(false)}
        title={t('attach')}
        submitLabel={t('attach')}
        busy={doAttach.isPending}
        onSubmit={() => doAttach.mutate({ walletId: activeWallet, couponId: value.trim() })}
      >
        <Field label={t('couponId')} value={value} onChange={(event) => setValue(event.target.value)} mono required />
      </FormDialog>

      <ConfirmDialog
        open={revokeOpen}
        onClose={() => setRevokeOpen(false)}
        title={tc('confirm.revokeRedemption')}
        description={tc('confirm.revokeRedemptionHint')}
        confirmLabel={t('revoke')}
        busy={doRevoke.isPending}
        onConfirm={() => {
          if (selected?.id) doRevoke.mutate({ id: selected.id })
        }}
        impact={[
          { label: tc('fields.name'), value: selected?.couponName ?? '' },
          {
            label: t('discountTotal'),
            value: <Money minor={selected?.discountTotal ?? 0} currency={selected?.currency} />,
          },
        ]}
      />
    </div>
  )
}
