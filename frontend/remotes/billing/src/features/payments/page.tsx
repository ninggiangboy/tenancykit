import { permissions } from '@suite/api-client'
import type { PaymentResponse } from '@suite/api-client/billing'
import { createTopUp, requestRefund } from '@suite/api-client/billing'
import { payment1Options, payments1Options } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, CopyId, Field, Money, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useT } from '../../i18n'
import {
  asListQuery,
  ConfirmDialog,
  DetailRow,
  DetailRows,
  FormDialog,
  IdValue,
  type ListSpec,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useListState,
} from '../../lib/page'
import { useAction, useApi, useToast } from '../../lib/runtime'

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
        { value: 'CANCELED', label: 'Canceled' },
        { value: 'REFUNDED', label: 'Refunded' },
      ],
    },
  ],
}

export function PaymentsPage({ state }: { state: UrlState }) {
  const t = useT('payments')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.payments_1)
  const canRefund = usePermission(permissions.billing.requestRefund)
  const canTopUp = usePermission(permissions.billing.createTopUp)
  const list = useListState(state, spec)

  const query = useQuery({
    ...payments1Options({
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

  const [refundTarget, setRefundTarget] = useState<PaymentResponse | null>(null)
  const [refundAmount, setRefundAmount] = useState('')
  const [refundReason, setRefundReason] = useState('')
  const [topUpOpen, setTopUpOpen] = useState(false)
  const [topUp, setTopUp] = useState({ walletId: '', amount: '', currency: 'USD' })
  const [checkout, setCheckout] = useState<string | null>(null)

  const refund = useAction(
    (vars: { paymentId: string; amount: number; currency: string; reason: string }) =>
      requestRefund({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        path: { id: vars.paymentId },
        body: { amount: vars.amount, currency: vars.currency, reason: vars.reason || undefined },
      }),
    {
      onSuccess: () => {
        setRefundTarget(null)
        setRefundAmount('')
        setRefundReason('')
        toast({ title: t('refunded'), tone: 'success' })
      },
    },
  )

  const createTopUpPayment = useAction(
    (vars: { walletId: string; amount: number; currency: string }) =>
      createTopUp({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: { walletId: vars.walletId, amount: vars.amount, currency: vars.currency },
      }),
    {
      onSuccess: (result) => {
        setTopUpOpen(false)
        setTopUp({ walletId: '', amount: '', currency: 'USD' })
        setCheckout(result.data.checkoutUrl ?? null)
        toast({ title: tc('toast.created'), tone: 'success' })
      },
    },
  )

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
        primary={
          <PermissionGate allowed={canTopUp}>
            <Button icon="plus" onClick={() => setTopUpOpen(true)}>
              {t('topUp')}
            </Button>
          </PermissionGate>
        }
        columns={[
          {
            key: 'amount',
            header: tc('fields.amount'),
            render: (row) => (
              <span className="flex flex-col">
                <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />
                <span className="text-caption text-muted-foreground">{row.walletId}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'customer_id',
            header: tc('fields.customer'),
            render: (row) => <IdValue value={row.customerId} />,
          },
          { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={`${selected.amount?.currency ?? ''} ${selected.status ?? ''}`}
          description={selected.walletId}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canRefund}>
              <Button
                variant="destructive"
                size="sm"
                icon="undo"
                onClick={() => {
                  const remaining = (selected.amount?.amount ?? 0) - (selected.refunded?.amount ?? 0)
                  setRefundAmount(String(remaining))
                  setRefundTarget(selected)
                }}
              >
                {t('refund')}
              </Button>
            </PermissionGate>
          }
        >
          <PaymentDetails payment={selected} />
        </RecordSheet>
      )}

      <ConfirmDialog
        open={Boolean(refundTarget)}
        onClose={() => setRefundTarget(null)}
        title={t('refundTitle')}
        description={tc('confirm.refundHint')}
        confirmLabel={t('refund')}
        busy={refund.isPending}
        destructive={false}
        onConfirm={() => {
          if (!refundTarget?.id) return
          const amount = Number(refundAmount)
          if (!Number.isFinite(amount) || amount <= 0) return
          refund.mutate({
            paymentId: refundTarget.id,
            amount,
            currency: refundTarget.amount?.currency ?? 'USD',
            reason: refundReason,
          })
        }}
        impact={[
          {
            label: tc('fields.amount'),
            value: <Money minor={refundTarget?.amount?.amount ?? 0} currency={refundTarget?.amount?.currency} />,
          },
          {
            label: t('refundable'),
            value: (
              <Money
                minor={(refundTarget?.amount?.amount ?? 0) - (refundTarget?.refunded?.amount ?? 0)}
                currency={refundTarget?.amount?.currency}
              />
            ),
          },
        ]}
      >
        <Field
          label={t('refundAmount')}
          type="number"
          min={1}
          value={refundAmount}
          onChange={(event) => setRefundAmount(event.target.value)}
        />
        <Field
          label={tc('fields.reason')}
          value={refundReason}
          onChange={(event) => setRefundReason(event.target.value)}
        />
      </ConfirmDialog>

      <FormDialog
        open={topUpOpen}
        onClose={() => setTopUpOpen(false)}
        title={t('topUpTitle')}
        submitLabel={t('topUp')}
        busy={createTopUpPayment.isPending}
        onSubmit={() =>
          createTopUpPayment.mutate({
            walletId: topUp.walletId.trim(),
            amount: Number(topUp.amount),
            currency: topUp.currency.trim().toUpperCase(),
          })
        }
      >
        <Field
          label={t('walletId')}
          value={topUp.walletId}
          onChange={(event) => setTopUp({ ...topUp, walletId: event.target.value })}
          mono
          required
        />
        <Field
          label={tc('fields.amount')}
          type="number"
          min={1}
          value={topUp.amount}
          onChange={(event) => setTopUp({ ...topUp, amount: event.target.value })}
          hint={t('minorUnits')}
        />
        <Field
          label={tc('fields.currency')}
          value={topUp.currency}
          onChange={(event) => setTopUp({ ...topUp, currency: event.target.value })}
          mono
        />
      </FormDialog>

      <ConfirmDialog
        open={Boolean(checkout)}
        onClose={() => setCheckout(null)}
        title={t('topUpTitle')}
        description={tc('fields.redirectTo')}
        confirmLabel={tc('actions.close')}
        destructive={false}
        onConfirm={() => setCheckout(null)}
        impact={[{ label: tc('fields.redirectTo'), value: <CopyId value={checkout ?? ''} /> }]}
      />
    </>
  )
}

function PaymentDetails({ payment }: { payment: PaymentResponse }) {
  const t = useT('payments')
  const tc = useT('common')
  const { billing } = useApi()
  const detail = useQuery({
    ...payment1Options({ client: billing, throwOnError: true, path: { id: payment.id ?? '' } }),
    enabled: Boolean(payment.id),
  })
  const refunds = detail.data?.refunds ?? []
  return (
    <div className="flex flex-col gap-4">
      <DetailRows>
        <DetailRow label={tc('fields.amount')}>
          <Money minor={payment.amount?.amount ?? 0} currency={payment.amount?.currency} />
        </DetailRow>
        <DetailRow label={t('refundable')}>
          <Money
            minor={(payment.amount?.amount ?? 0) - (payment.refunded?.amount ?? 0)}
            currency={payment.amount?.currency}
          />
        </DetailRow>
        <DetailRow label={tc('fields.wallet')}>
          <IdValue value={payment.walletId} />
        </DetailRow>
        <DetailRow label={tc('fields.customer')}>
          <IdValue value={payment.customerId} />
        </DetailRow>
        <DetailRow label={tc('fields.status')}>
          <StatusBadge status={payment.status} />
        </DetailRow>
        <DetailRow label={tc('fields.created')}>
          <Timestamp value={payment.createdAt} />
        </DetailRow>
        <DetailRow label={tc('fields.id')}>
          <IdValue value={payment.id} />
        </DetailRow>
      </DetailRows>
      <section className="flex flex-col gap-2">
        <h2 className="text-heading text-foreground">{t('refund')}</h2>
        {refunds.length > 0 ? (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {refunds.map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                <span className="flex items-center gap-2">
                  <StatusBadge status={entry.status} />
                  <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
                </span>
                <span className="text-muted-foreground">{entry.reason}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-small text-muted-foreground">{tc('state.none')}</p>
        )}
      </section>
    </div>
  )
}
