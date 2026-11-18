import { permissions } from '@suite/api-client'
import { charge as createCharge } from '@suite/api-client/billing'
import { chargesOptions } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, Field, Money, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useT } from '../../i18n'
import {
  asListQuery,
  DetailRow,
  DetailRows,
  FormDialog,
  IdValue,
  type ListSpec,
  RecordSheet,
  ResourcePage,
  useListState,
} from '../../lib/page'
import { useAction, useApi, useToast } from '../../lib/runtime'

const spec: ListSpec = {
  sortable: ['amount', 'id'],
  facets: [
    {
      key: 'status',
      label: 'Status',
      field: 'status',
      options: [
        { value: 'SUCCEEDED', label: 'Succeeded' },
        { value: 'FAILED', label: 'Failed' },
      ],
    },
  ],
}

interface Form {
  walletId: string
  amount: string
  currency: string
  reference: string
  usageRef: string
}

const empty: Form = { walletId: '', amount: '', currency: 'USD', reference: '', usageRef: '' }

export function ChargesPage({ state }: { state: UrlState }) {
  const t = useT('charges')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.charges)
  const canCreate = usePermission(permissions.billing.charge)
  const list = useListState(state, spec)

  const query = useQuery({
    ...chargesOptions({
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
  const [form, setForm] = useState<Form>(empty)

  const create = useAction(
    (vars: Form) =>
      createCharge({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: {
          walletId: vars.walletId.trim(),
          amount: Number(vars.amount),
          currency: vars.currency.trim().toUpperCase(),
          reference: vars.reference.trim() || undefined,
          usageRef: vars.usageRef.trim() || undefined,
        },
      }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setForm(empty)
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
          <PermissionGate allowed={canCreate}>
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
                <Money minor={row.grossAmount?.amount ?? row.amount?.amount ?? 0} currency={row.amount?.currency} />
                <span className="text-caption text-muted-foreground">{row.reference}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'reference',
            header: tc('fields.reference'),
            render: (row) => <span className="font-mono text-code">{row.reference ?? tc('state.none')}</span>,
          },
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
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.amount')}>
                <Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />
              </DetailRow>
              <DetailRow label={t('discounts')}>{selected.discounts?.length ?? 0}</DetailRow>
              <DetailRow label={tc('fields.status')}>
                <StatusBadge status={selected.status} />
              </DetailRow>
              <DetailRow label={tc('fields.reference')}>{selected.reference ?? tc('state.none')}</DetailRow>
              <DetailRow label={t('usageRef')}>{selected.usageRef ?? tc('state.none')}</DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>
            {selected.discounts && selected.discounts.length > 0 && (
              <section className="flex flex-col gap-1">
                <h3 className="text-body-strong text-foreground">{t('discounts')}</h3>
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {selected.discounts.map((discount) => (
                    <li key={discount.redemptionId} className="flex justify-between gap-2 px-3 py-2 text-small">
                      <span>{discount.couponName ?? discount.couponId}</span>
                      <Money minor={discount.amount?.amount ?? 0} currency={discount.amount?.currency} />
                    </li>
                  ))}
                </ul>
              </section>
            )}
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
          label={t('reference')}
          value={form.reference}
          onChange={(event) => setForm({ ...form, reference: event.target.value })}
          optional
        />
        <Field
          label={t('usageRef')}
          value={form.usageRef}
          onChange={(event) => setForm({ ...form, usageRef: event.target.value })}
          optional
        />
      </FormDialog>
    </>
  )
}
