import { permissions } from '@suite/api-client'
import type { LedgerEntryResponse, WalletResponse } from '@suite/api-client/billing'
import { openWallet, reverse } from '@suite/api-client/billing'
import { ledger1Options, walletsOptions } from '@suite/api-client/billing/query'
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
  sortable: ['created_at', 'id'],
  facets: [
    {
      key: 'currency',
      label: 'Currency',
      field: 'currency',
      options: [
        { value: 'USD', label: 'USD' },
        { value: 'VND', label: 'VND' },
        { value: 'EUR', label: 'EUR' },
      ],
    },
  ],
}

export function WalletsPage({ state }: { state: UrlState }) {
  const t = useT('wallets')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.wallets)
  const canManage = usePermission(permissions.billing.openWallet)
  const canReverse = usePermission(permissions.billing.reverse)
  const list = useListState(state, spec)

  const query = useQuery({
    ...walletsOptions({
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
  const [form, setForm] = useState({ customerId: '', currency: 'USD', mode: 'PREPAID', creditLimit: '' })

  const create = useAction(
    (vars: { customerId: string; currency: string; mode: string; creditLimit: string }) =>
      openWallet({
        client: billing,
        throwOnError: true,
        body: {
          customerId: vars.customerId.trim(),
          currency: vars.currency.trim().toUpperCase(),
          mode: vars.mode.trim(),
          creditLimit: vars.creditLimit ? Number(vars.creditLimit) : undefined,
        },
      }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setForm({ customerId: '', currency: 'USD', mode: 'PREPAID', creditLimit: '' })
        toast({ title: t('created'), tone: 'success' })
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
            key: 'balance',
            header: tc('fields.balance'),
            render: (row) => (
              <span className="flex flex-col">
                <Money minor={row.balance?.amount ?? 0} currency={row.balance?.currency ?? row.currency} />
                <span className="text-caption text-muted-foreground">{row.currency}</span>
              </span>
            ),
          },
          {
            key: 'mode',
            header: tc('fields.mode'),
            render: (row) => <StatusBadge tone="neutral">{row.mode}</StatusBadge>,
          },
          { key: 'customer_id', header: tc('fields.customer'), render: (row) => <IdValue value={row.customerId} /> },
          { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={
            <Money minor={selected.balance?.amount ?? 0} currency={selected.balance?.currency ?? selected.currency} />
          }
          description={selected.customerId}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.balance')}>
                <Money
                  minor={selected.balance?.amount ?? 0}
                  currency={selected.balance?.currency ?? selected.currency}
                />
              </DetailRow>
              <DetailRow label={tc('fields.held')}>
                <Money minor={selected.held?.amount ?? 0} currency={selected.held?.currency ?? selected.currency} />
              </DetailRow>
              <DetailRow label={tc('fields.available')}>
                <Money
                  minor={selected.available?.amount ?? 0}
                  currency={selected.available?.currency ?? selected.currency}
                />
              </DetailRow>
              <DetailRow label={tc('fields.creditLimit')}>
                <Money
                  minor={selected.creditLimit?.amount ?? 0}
                  currency={selected.creditLimit?.currency ?? selected.currency}
                />
              </DetailRow>
              <DetailRow label={tc('fields.mode')}>{selected.mode}</DetailRow>
              <DetailRow label={tc('fields.customer')}>
                <IdValue value={selected.customerId} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>
            <Ledger wallet={selected} canReverse={canReverse} />
          </div>
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
          label={t('customerId')}
          value={form.customerId}
          onChange={(event) => setForm({ ...form, customerId: event.target.value })}
          mono
          required
        />
        <Field
          label={t('currency')}
          value={form.currency}
          onChange={(event) => setForm({ ...form, currency: event.target.value })}
          mono
        />
        <Field
          label={t('mode')}
          value={form.mode}
          onChange={(event) => setForm({ ...form, mode: event.target.value })}
          mono
        />
        <Field
          label={t('creditLimit')}
          type="number"
          value={form.creditLimit}
          onChange={(event) => setForm({ ...form, creditLimit: event.target.value })}
          optional
        />
      </FormDialog>
    </>
  )
}

function Ledger({ wallet, canReverse }: { wallet: WalletResponse; canReverse: boolean }) {
  const t = useT('wallets')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const query = useQuery({
    ...ledger1Options({ client: billing, throwOnError: true, path: { id: wallet.id ?? '' } }),
    enabled: Boolean(wallet.id),
  })
  const entries = query.data?.data ?? []
  const [target, setTarget] = useState<LedgerEntryResponse | null>(null)
  const [reason, setReason] = useState('')
  const reverseEntry = useAction(
    (vars: { id: string; reason: string }) =>
      reverse({ client: billing, throwOnError: true, path: { id: vars.id }, body: { reason: vars.reason } }),
    {
      onSuccess: () => {
        setTarget(null)
        setReason('')
        toast({ title: t('reversed'), tone: 'success' })
      },
    },
  )

  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-heading text-foreground">{t('ledger')}</h2>
      {entries.length > 0 ? (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {entries.map((entry) => (
            <li key={entry.id} className="flex items-start justify-between gap-2 px-3 py-2 text-small">
              <span className="flex flex-col">
                <span className="flex items-center gap-2">
                  <span className="font-mono text-caption text-muted-foreground">#{entry.sequence}</span>
                  <span className="text-foreground">{entry.type}</span>
                  <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
                </span>
                <span className="text-caption text-muted-foreground">
                  {entry.reference ?? tc('state.none')} · <Timestamp value={entry.createdAt} />
                </span>
              </span>
              <PermissionGate allowed={canReverse && entry.type !== 'REVERSAL'}>
                <Button variant="ghost" size="sm" onClick={() => setTarget(entry)}>
                  {t('reverse')}
                </Button>
              </PermissionGate>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-small text-muted-foreground">{t('emptyLedger')}</p>
      )}
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.reverseEntry')}
        description={tc('confirm.reverseEntryHint')}
        confirmLabel={t('reverse')}
        busy={reverseEntry.isPending}
        onConfirm={() => {
          if (target?.id) reverseEntry.mutate({ id: target.id, reason })
        }}
        impact={[
          { label: tc('fields.sequence'), value: target?.sequence ?? '' },
          {
            label: tc('fields.amount'),
            value: <Money minor={target?.amount?.amount ?? 0} currency={target?.amount?.currency} />,
          },
        ]}
      >
        <Field label={tc('fields.reason')} value={reason} onChange={(event) => setReason(event.target.value)} />
      </ConfirmDialog>
    </section>
  )
}
