import { permissions } from '@suite/api-client'
import type { SubscriptionResponse } from '@suite/api-client/billing'
import { cancel, subscribe } from '@suite/api-client/billing'
import { subscription1Options, subscriptionsOptions } from '@suite/api-client/billing/query'
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
        { value: 'ACTIVE', label: 'Active' },
        { value: 'PAST_DUE', label: 'Past due' },
        { value: 'CANCELED', label: 'Canceled' },
      ],
    },
  ],
}

export function SubscriptionsPage({ state }: { state: UrlState }) {
  const t = useT('subscriptions')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.subscriptions)
  const canManage = usePermission(permissions.billing.subscribe)
  const list = useListState(state, spec)

  const query = useQuery({
    ...subscriptionsOptions({
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
  const [subscription, setSubscription] = useState({ walletId: '', planId: '' })
  const [cancelOpen, setCancelOpen] = useState(false)

  const createSubscription = useAction(
    (vars: { walletId: string; planId: string }) =>
      subscribe({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: { walletId: vars.walletId, planId: vars.planId },
      }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setSubscription({ walletId: '', planId: '' })
        toast({ title: tc('toast.created'), tone: 'success' })
      },
    },
  )

  const cancelSubscription = useAction(
    (vars: { id: string }) => cancel({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setCancelOpen(false)
        toast({ title: t('canceled'), tone: 'success' })
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
            key: 'plan_code',
            header: tc('fields.plan'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.planCode}</span>
                <span className="text-caption text-muted-foreground">{row.cycle}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'customer_id',
            header: tc('fields.customer'),
            render: (row) => <IdValue value={row.customerId} />,
          },
          {
            key: 'current_period_end',
            header: t('nextRenewal'),
            render: (row) => <Timestamp value={row.currentPeriodEnd} />,
          },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={selected.planCode ?? ''}
          description={selected.status}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canManage && selected.status !== 'CANCELED'}>
              <Button variant="destructive" size="sm" icon="ban" onClick={() => setCancelOpen(true)}>
                {t('cancel')}
              </Button>
            </PermissionGate>
          }
        >
          <SubscriptionDetail subscription={selected} />
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={tc('actions.create')}
        busy={createSubscription.isPending}
        onSubmit={() => createSubscription.mutate(subscription)}
      >
        <Field
          label={t('walletId')}
          value={subscription.walletId}
          onChange={(event) => setSubscription({ ...subscription, walletId: event.target.value })}
          mono
          required
        />
        <Field
          label={t('planId')}
          value={subscription.planId}
          onChange={(event) => setSubscription({ ...subscription, planId: event.target.value })}
          mono
          required
        />
      </FormDialog>

      <ConfirmDialog
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        title={tc('confirm.cancelSubscription')}
        description={tc('confirm.cancelSubscriptionHint')}
        confirmLabel={t('cancel')}
        busy={cancelSubscription.isPending}
        onConfirm={() => {
          if (selected?.id) cancelSubscription.mutate({ id: selected.id })
        }}
        impact={[
          { label: tc('fields.plan'), value: selected?.planCode ?? '' },
          { label: tc('fields.status'), value: <StatusBadge status={selected?.status} /> },
          { label: tc('fields.customer'), value: <IdValue value={selected?.customerId} /> },
        ]}
      />
    </>
  )
}

function SubscriptionDetail({ subscription }: { subscription: SubscriptionResponse }) {
  const t = useT('subscriptions')
  const tc = useT('common')
  const { billing } = useApi()
  const detail = useQuery({
    ...subscription1Options({ client: billing, throwOnError: true, path: { id: subscription.id ?? '' } }),
    enabled: Boolean(subscription.id),
  })
  const history = detail.data?.history ?? []
  return (
    <div className="flex flex-col gap-4">
      <DetailRows>
        <DetailRow label={tc('fields.plan')}>{subscription.planCode}</DetailRow>
        <DetailRow label={tc('fields.status')}>
          <StatusBadge status={subscription.status} />
        </DetailRow>
        <DetailRow label={t('cycle')}>{subscription.cycle}</DetailRow>
        <DetailRow label={t('periods')}>{subscription.periodsCharged}</DetailRow>
        <DetailRow label={tc('fields.customer')}>
          <IdValue value={subscription.customerId} />
        </DetailRow>
        <DetailRow label={tc('fields.wallet')}>
          <IdValue value={subscription.walletId} />
        </DetailRow>
        <DetailRow label={tc('fields.periodStart')}>
          <Timestamp value={subscription.currentPeriodStart} />
        </DetailRow>
        <DetailRow label={tc('fields.periodEnd')}>
          <Timestamp value={subscription.currentPeriodEnd} />
        </DetailRow>
        <DetailRow label={t('nextRenewal')}>
          <Timestamp value={subscription.nextRenewalAt} />
        </DetailRow>
        <DetailRow label={tc('fields.id')}>
          <IdValue value={subscription.id} />
        </DetailRow>
      </DetailRows>
      <section className="flex flex-col gap-2">
        <h2 className="text-heading text-foreground">{t('history')}</h2>
        {history.length > 0 ? (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {history.map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                <span className="flex flex-col">
                  <span className="text-foreground">{entry.type}</span>
                  <span className="text-caption text-muted-foreground">
                    <Timestamp value={entry.occurredAt} />
                  </span>
                </span>
                <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
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
