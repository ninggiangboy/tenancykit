import { permissions } from '@suite/api-client'
import type { InvoiceResponse } from '@suite/api-client/billing'
import { invoiceCharge, markUncollectible, pay, voidInvoice } from '@suite/api-client/billing'
import { invoice1Options, invoicesOptions } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, Field, Money, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
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
  sortable: ['id', 'created_at', 'total'],
  searchField: 'number',
  searchOp: 'starts_with',
  searchPlaceholder: 'Search by invoice number',
  views: [
    { id: 'all', label: 'All' },
    { id: 'open', label: 'Open', filters: [{ field: 'status', op: 'eq', value: 'OPEN' }] },
    { id: 'paid', label: 'Paid', filters: [{ field: 'status', op: 'eq', value: 'PAID' }] },
    { id: 'void', label: 'Void', filters: [{ field: 'status', op: 'eq', value: 'VOID' }] },
  ],
}

export function InvoicesPage({ state }: { state: UrlState }) {
  const t = useT('invoices')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.invoices)
  const canCreate = usePermission(permissions.billing.invoiceCharge)
  const canManage = usePermission(permissions.billing.voidInvoice)
  const canPay = usePermission(permissions.billing.pay)
  const list = useListState(state, spec)

  const query = useQuery({
    ...invoicesOptions({
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

  const [createOpen, setCreateOpen] = useState(false)
  const [chargeId, setChargeId] = useState('')
  const [voidOpen, setVoidOpen] = useState(false)
  const [voidReason, setVoidReason] = useState('')
  const [uncollectibleOpen, setUncollectibleOpen] = useState(false)
  const [payOpen, setPayOpen] = useState(false)

  const createInvoice = useAction(
    (vars: { chargeId: string }) =>
      invoiceCharge({ client: billing, throwOnError: true, body: { chargeId: vars.chargeId.trim() } }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setChargeId('')
        toast({ title: tc('toast.created'), tone: 'success' })
      },
    },
  )

  const voidIt = useAction(
    (vars: { id: string; reason: string }) =>
      voidInvoice({ client: billing, throwOnError: true, path: { id: vars.id }, body: { reason: vars.reason } }),
    {
      onSuccess: () => {
        setVoidOpen(false)
        setVoidReason('')
        toast({ title: t('voided'), tone: 'success' })
      },
    },
  )

  const writeOff = useAction(
    (vars: { id: string }) => markUncollectible({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setUncollectibleOpen(false)
        toast({ title: t('uncollectibleDone'), tone: 'success' })
      },
    },
  )

  const recordPayment = useAction(
    (vars: { id: string }) =>
      pay({
        client: billing,
        throwOnError: true,
        path: { id: vars.id },
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
      }),
    {
      onSuccess: () => {
        setPayOpen(false)
        toast({ title: t('paid'), tone: 'success' })
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
          <PermissionGate allowed={canCreate}>
            <Button icon="plus" onClick={() => setCreateOpen(true)}>
              {t('create')}
            </Button>
          </PermissionGate>
        }
        columns={[
          {
            key: 'total',
            header: tc('fields.amount'),
            render: (row) => (
              <span className="flex flex-col">
                <Money minor={row.total?.amount ?? 0} currency={row.total?.currency} />
                <span className="font-mono text-caption text-muted-foreground">{row.number}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'customer_id',
            header: tc('fields.customer'),
            render: (row) => <IdValue value={row.customerId} />,
          },
          { key: 'issued_at', header: tc('fields.periodStart'), render: (row) => <Timestamp value={row.issuedAt} /> },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={selected.number ?? ''}
          description={`${selected.status ?? ''}`}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <>
              <PermissionGate allowed={canPay}>
                <Button variant="outline" size="sm" icon="credit-card" onClick={() => setPayOpen(true)}>
                  {t('pay')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canManage}>
                <Button variant="outline" size="sm" icon="ban" onClick={() => setUncollectibleOpen(true)}>
                  {t('uncollectible')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canManage}>
                <Button variant="destructive" size="sm" icon="x-circle" onClick={() => setVoidOpen(true)}>
                  {t('void')}
                </Button>
              </PermissionGate>
            </>
          }
        >
          <InvoiceDetail invoice={selected} />
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={tc('actions.create')}
        busy={createInvoice.isPending}
        onSubmit={() => createInvoice.mutate({ chargeId })}
      >
        <Field
          label={t('chargeId')}
          value={chargeId}
          onChange={(event) => setChargeId(event.target.value)}
          mono
          required
        />
      </FormDialog>

      <ConfirmDialog
        open={voidOpen}
        onClose={() => setVoidOpen(false)}
        title={tc('confirm.voidInvoice')}
        description={tc('confirm.voidInvoiceHint')}
        confirmLabel={t('void')}
        busy={voidIt.isPending}
        onConfirm={() => {
          if (selected?.id) voidIt.mutate({ id: selected.id, reason: voidReason })
        }}
        impact={[
          { label: t('number'), value: selected?.number ?? '' },
          {
            label: tc('fields.amount'),
            value: <Money minor={selected?.total?.amount ?? 0} currency={selected?.total?.currency} />,
          },
        ]}
      >
        <Field label={t('voidReason')} value={voidReason} onChange={(event) => setVoidReason(event.target.value)} />
      </ConfirmDialog>

      <ConfirmDialog
        open={uncollectibleOpen}
        onClose={() => setUncollectibleOpen(false)}
        title={tc('confirm.uncollectible')}
        description={tc('confirm.uncollectibleHint')}
        confirmLabel={t('uncollectible')}
        busy={writeOff.isPending}
        onConfirm={() => {
          if (selected?.id) writeOff.mutate({ id: selected.id })
        }}
        impact={[
          { label: t('number'), value: selected?.number ?? '' },
          {
            label: tc('fields.amount'),
            value: <Money minor={selected?.total?.amount ?? 0} currency={selected?.total?.currency} />,
          },
        ]}
      />

      <ConfirmDialog
        open={payOpen}
        onClose={() => setPayOpen(false)}
        title={t('payTitle')}
        confirmLabel={t('pay')}
        busy={recordPayment.isPending}
        destructive={false}
        onConfirm={() => {
          if (selected?.id) recordPayment.mutate({ id: selected.id })
        }}
        impact={[
          { label: t('number'), value: selected?.number ?? '' },
          {
            label: tc('fields.amount'),
            value: <Money minor={selected?.total?.amount ?? 0} currency={selected?.total?.currency} />,
          },
        ]}
      />
    </>
  )
}

function InvoiceDetail({ invoice }: { invoice: InvoiceResponse }) {
  const t = useT('invoices')
  const tc = useT('common')
  const { billing } = useApi()
  const detail = useQuery({
    ...invoice1Options({ client: billing, throwOnError: true, path: { id: invoice.id ?? '' } }),
    enabled: Boolean(invoice.id),
  })
  const items = detail.data?.items ?? []
  const payments = detail.data?.payments ?? []
  return (
    <div className="flex flex-col gap-4">
      <DetailRows>
        <DetailRow label={tc('fields.amount')}>
          <Money minor={invoice.total?.amount ?? 0} currency={invoice.total?.currency} />
        </DetailRow>
        <DetailRow label={tc('fields.status')}>
          <StatusBadge status={invoice.status} />
        </DetailRow>
        <DetailRow label={tc('fields.customer')}>
          <IdValue value={invoice.customerId} />
        </DetailRow>
        <DetailRow label={tc('fields.wallet')}>
          <IdValue value={invoice.walletId} />
        </DetailRow>
        <DetailRow label={tc('fields.periodStart')}>
          <Timestamp value={invoice.periodStart} />
        </DetailRow>
        <DetailRow label={tc('fields.periodEnd')}>
          <Timestamp value={invoice.periodEnd} />
        </DetailRow>
        <DetailRow label={tc('fields.id')}>
          <IdValue value={invoice.id} />
        </DetailRow>
      </DetailRows>
      <section className="flex flex-col gap-2">
        <h2 className="text-heading text-foreground">{t('items')}</h2>
        {items.length > 0 ? (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {items.map((item) => (
              <li key={item.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                <span className="flex flex-col">
                  <span className="text-foreground">{item.description}</span>
                  <span className="text-caption text-muted-foreground">{item.source}</span>
                </span>
                <Money minor={item.amount?.amount ?? 0} currency={item.amount?.currency} />
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-small text-muted-foreground">{tc('state.none')}</p>
        )}
      </section>
      <section className="flex flex-col gap-2">
        <h2 className="text-heading text-foreground">{t('payments')}</h2>
        {payments.length > 0 ? (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {payments.map((entry) => (
              <li key={entry.paymentId} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                <span className="flex items-center gap-2">
                  <StatusBadge status={entry.status} />
                  <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
                </span>
                <IdValue value={entry.paymentId} />
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
