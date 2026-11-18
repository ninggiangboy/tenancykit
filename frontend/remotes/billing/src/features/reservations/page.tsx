import { permissions } from '@suite/api-client'
import { capture, release, reserve } from '@suite/api-client/billing'
import { reservationsOptions } from '@suite/api-client/billing/query'
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
  sortable: ['id'],
  facets: [
    {
      key: 'status',
      label: 'Status',
      field: 'status',
      options: [
        { value: 'HELD', label: 'Held' },
        { value: 'CAPTURED', label: 'Captured' },
        { value: 'RELEASED', label: 'Released' },
        { value: 'EXPIRED', label: 'Expired' },
      ],
    },
  ],
}

export function ReservationsPage({ state }: { state: UrlState }) {
  const t = useT('reservations')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.reservations)
  const canManage = usePermission(permissions.billing.reserve)
  const list = useListState(state, spec)

  const query = useQuery({
    ...reservationsOptions({
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
  const [form, setForm] = useState({ walletId: '', amount: '', currency: 'USD', ttlSeconds: '', reference: '' })
  const [captureOpen, setCaptureOpen] = useState(false)
  const [captured, setCaptured] = useState({ amount: '', currency: 'USD', reference: '' })
  const [releaseOpen, setReleaseOpen] = useState(false)

  const create = useAction(
    (vars: typeof form) =>
      reserve({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: {
          walletId: vars.walletId.trim(),
          amount: Number(vars.amount),
          currency: vars.currency.trim().toUpperCase(),
          ttlSeconds: vars.ttlSeconds ? Number(vars.ttlSeconds) : undefined,
          reference: vars.reference.trim() || undefined,
        },
      }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setForm({ walletId: '', amount: '', currency: 'USD', ttlSeconds: '', reference: '' })
        toast({ title: t('created'), tone: 'success' })
      },
    },
  )

  const captureReservation = useAction(
    (vars: { id: string; amount: number; currency: string; reference: string }) =>
      capture({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        path: { id: vars.id },
        body: { amount: vars.amount, currency: vars.currency, reference: vars.reference || undefined },
      }),
    {
      onSuccess: () => {
        setCaptureOpen(false)
        toast({ title: t('captured'), tone: 'success' })
      },
    },
  )

  const releaseReservation = useAction(
    (vars: { id: string }) => release({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setReleaseOpen(false)
        toast({ title: t('released'), tone: 'success' })
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
          <PermissionGate allowed={canManage}>
            <Button icon="plus" onClick={() => setCreateOpen(true)}>
              {t('create')}
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
                <span className="text-caption text-muted-foreground">{row.reference}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          { key: 'wallet_id', header: tc('fields.wallet'), render: (row) => <IdValue value={row.walletId} /> },
          { key: 'expires_at', header: t('expires'), render: (row) => <Timestamp value={row.expiresAt} /> },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={<Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />}
          description={selected.status}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canManage && selected.status === 'HELD'}>
              <Button
                variant="outline"
                size="sm"
                icon="check"
                onClick={() => {
                  setCaptured({
                    amount: String(selected.amount?.amount ?? 0),
                    currency: selected.amount?.currency ?? 'USD',
                    reference: selected.reference ?? '',
                  })
                  setCaptureOpen(true)
                }}
              >
                {t('capture')}
              </Button>
              <Button variant="destructive" size="sm" icon="undo" onClick={() => setReleaseOpen(true)}>
                {t('release')}
              </Button>
            </PermissionGate>
          }
        >
          <DetailRows>
            <DetailRow label={tc('fields.amount')}>
              <Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />
            </DetailRow>
            <DetailRow label={t('capturedAmount')}>
              <Money minor={selected.captured?.amount ?? 0} currency={selected.captured?.currency} />
            </DetailRow>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={selected.status} />
            </DetailRow>
            <DetailRow label={tc('fields.wallet')}>
              <IdValue value={selected.walletId} />
            </DetailRow>
            <DetailRow label={tc('fields.reference')}>{selected.reference ?? tc('state.none')}</DetailRow>
            <DetailRow label={t('expires')}>
              <Timestamp value={selected.expiresAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <IdValue value={selected.id} />
            </DetailRow>
          </DetailRows>
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={tc('actions.create')}
        busy={create.isPending}
        onSubmit={() => create.mutate(form)}
      >
        <Field
          label={t('walletId')}
          value={form.walletId}
          onChange={(event) => setForm({ ...form, walletId: event.target.value })}
          mono
          required
        />
        <Field
          label={t('amount')}
          type="number"
          value={form.amount}
          onChange={(event) => setForm({ ...form, amount: event.target.value })}
        />
        <Field
          label={t('currency')}
          value={form.currency}
          onChange={(event) => setForm({ ...form, currency: event.target.value })}
          mono
        />
        <Field
          label={t('ttl')}
          type="number"
          value={form.ttlSeconds}
          onChange={(event) => setForm({ ...form, ttlSeconds: event.target.value })}
          optional
        />
        <Field
          label={t('reference')}
          value={form.reference}
          onChange={(event) => setForm({ ...form, reference: event.target.value })}
          optional
        />
      </FormDialog>

      <FormDialog
        open={captureOpen}
        onClose={() => setCaptureOpen(false)}
        title={t('capture')}
        description={tc('confirm.captureReservationHint')}
        submitLabel={t('capture')}
        busy={captureReservation.isPending}
        onSubmit={() => {
          if (selected?.id) {
            captureReservation.mutate({
              id: selected.id,
              amount: Number(captured.amount),
              currency: captured.currency,
              reference: captured.reference,
            })
          }
        }}
      >
        <Field
          label={t('amount')}
          type="number"
          value={captured.amount}
          onChange={(event) => setCaptured({ ...captured, amount: event.target.value })}
        />
        <Field
          label={t('currency')}
          value={captured.currency}
          onChange={(event) => setCaptured({ ...captured, currency: event.target.value })}
          mono
        />
        <Field
          label={t('reference')}
          value={captured.reference}
          onChange={(event) => setCaptured({ ...captured, reference: event.target.value })}
          optional
        />
      </FormDialog>

      <ConfirmDialog
        open={releaseOpen}
        onClose={() => setReleaseOpen(false)}
        title={tc('confirm.releaseReservation')}
        description={tc('confirm.releaseReservationHint')}
        confirmLabel={t('release')}
        busy={releaseReservation.isPending}
        onConfirm={() => {
          if (selected?.id) releaseReservation.mutate({ id: selected.id })
        }}
        impact={[
          {
            label: tc('fields.amount'),
            value: <Money minor={selected?.amount?.amount ?? 0} currency={selected?.amount?.currency} />,
          },
          { label: tc('fields.wallet'), value: <IdValue value={selected?.walletId} /> },
        ]}
      />
    </>
  )
}
