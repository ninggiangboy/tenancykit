import { permissions } from '@suite/api-client'
import type { CouponResponse } from '@suite/api-client/billing'
import { addCode, createCoupon, deactivateCode, deactivateCoupon } from '@suite/api-client/billing'
import { codesOptions, couponsOptions } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, Field, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
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
  sortable: ['name', 'created_at', 'id'],
  facets: [
    {
      key: 'status',
      label: 'Status',
      field: 'status',
      options: [
        { value: 'ACTIVE', label: 'Active' },
        { value: 'DEACTIVATED', label: 'Deactivated' },
      ],
    },
  ],
}

interface Form {
  name: string
  currency: string
  benefitType: string
  basisPoints: string
  amountOff: string
  code: string
}

const empty: Form = {
  name: '',
  currency: 'USD',
  benefitType: 'PERCENT',
  basisPoints: '1000',
  amountOff: '',
  code: '',
}

export function CouponsPage({ state }: { state: UrlState }) {
  const t = useT('coupons')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.coupons)
  const canManage = usePermission(permissions.billing.createCoupon)
  const list = useListState(state, spec)

  const query = useQuery({
    ...couponsOptions({
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
  const [deactivateOpen, setDeactivateOpen] = useState(false)

  const create = useAction(
    (vars: Form) =>
      createCoupon({
        client: billing,
        throwOnError: true,
        body: {
          name: vars.name.trim(),
          currency: vars.currency.trim().toUpperCase(),
          benefit: {
            type: vars.benefitType.trim(),
            basisPoints: vars.basisPoints ? Number(vars.basisPoints) : undefined,
            amount: vars.amountOff ? Number(vars.amountOff) : undefined,
          },
          code: vars.code.trim() || undefined,
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

  const deactivate = useAction(
    (vars: { id: string; revokeRedemptions: boolean }) =>
      deactivateCoupon({
        client: billing,
        throwOnError: true,
        path: { id: vars.id },
        body: { revokeRedemptions: vars.revokeRedemptions },
      }),
    {
      onSuccess: () => {
        setDeactivateOpen(false)
        toast({ title: t('deactivated'), tone: 'success' })
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
            key: 'name',
            header: tc('fields.name'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.name}</span>
                <span className="text-caption text-muted-foreground">
                  {row.benefitType} · {row.percentBasisPoints ?? row.amountOff ?? row.creditAmount}
                </span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'redemptions',
            header: t('codes'),
            sortable: false,
            render: (row) => <span>{row.redemptionsCount ?? 0}</span>,
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
          title={selected.name ?? ''}
          description={selected.benefitType}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canManage && selected.status !== 'DEACTIVATED'}>
              <Button variant="destructive" size="sm" icon="ban" onClick={() => setDeactivateOpen(true)}>
                {t('deactivate')}
              </Button>
            </PermissionGate>
          }
        >
          <CouponDetail coupon={selected} canManage={canManage} />
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
          label={t('name')}
          value={form.name}
          onChange={(event) => setForm({ ...form, name: event.target.value })}
          required
        />
        <Field
          label={t('currency')}
          value={form.currency}
          onChange={(event) => setForm({ ...form, currency: event.target.value })}
          mono
        />
        <Field
          label={t('benefit')}
          value={form.benefitType}
          onChange={(event) => setForm({ ...form, benefitType: event.target.value })}
          mono
        />
        <Field
          label={t('percent')}
          type="number"
          value={form.basisPoints}
          onChange={(event) => setForm({ ...form, basisPoints: event.target.value })}
          hint={t('basisPointsHint')}
        />
        <Field
          label={t('amountOff')}
          type="number"
          value={form.amountOff}
          onChange={(event) => setForm({ ...form, amountOff: event.target.value })}
        />
        <Field
          label={t('code')}
          value={form.code}
          onChange={(event) => setForm({ ...form, code: event.target.value })}
          mono
          optional
        />
      </FormDialog>

      <ConfirmDialog
        open={deactivateOpen}
        onClose={() => setDeactivateOpen(false)}
        title={t('deactivate')}
        description={tc('confirm.irreversible')}
        confirmLabel={t('deactivate')}
        busy={deactivate.isPending}
        onConfirm={() => {
          if (selected?.id) deactivate.mutate({ id: selected.id, revokeRedemptions: false })
        }}
        impact={[
          { label: tc('fields.name'), value: selected?.name ?? '' },
          { label: t('codes'), value: selected?.redemptionsCount ?? 0 },
        ]}
      />
    </>
  )
}

function CouponDetail({ coupon, canManage }: { coupon: CouponResponse; canManage: boolean }) {
  const t = useT('coupons')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const [code, setCode] = useState('')
  const [max, setMax] = useState('')
  const codes = useQuery({
    ...codesOptions({ client: billing, throwOnError: true, path: { id: coupon.id ?? '' } }),
    enabled: Boolean(coupon.id),
  })
  const add = useAction(
    (vars: { code: string; max: string }) =>
      addCode({
        client: billing,
        throwOnError: true,
        path: { id: coupon.id ?? '' },
        body: { code: vars.code.trim(), maxRedemptions: vars.max ? Number(vars.max) : undefined },
      }),
    {
      onSuccess: () => {
        setCode('')
        setMax('')
        toast({ title: t('codeAdded'), tone: 'success' })
      },
    },
  )
  const deactivate = useAction(
    (vars: { id: string }) => deactivateCode({ client: billing, throwOnError: true, path: { id: vars.id } }),
    { onSuccess: () => toast({ title: t('deactivated'), tone: 'success' }) },
  )

  return (
    <div className="flex flex-col gap-4">
      <DetailRows>
        <DetailRow label={t('benefit')}>{coupon.benefitType}</DetailRow>
        <DetailRow label={t('percent')}>{coupon.percentBasisPoints ?? tc('state.none')}</DetailRow>
        <DetailRow label={t('amountOff')}>{coupon.amountOff ?? tc('state.none')}</DetailRow>
        <DetailRow label={t('currency')}>{coupon.currency}</DetailRow>
        <DetailRow label={t('stackable')}>{coupon.stackable ? tc('state.on') : tc('state.off')}</DetailRow>
        <DetailRow label={tc('fields.status')}>
          <StatusBadge status={coupon.status} />
        </DetailRow>
        <DetailRow label={tc('fields.id')}>
          <IdValue value={coupon.id} />
        </DetailRow>
      </DetailRows>
      <section className="flex flex-col gap-2">
        <h2 className="text-heading text-foreground">{t('codes')}</h2>
        {codes.data?.data && codes.data.data.length > 0 ? (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {codes.data.data.map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                <span className="flex items-center gap-2">
                  <span className="font-mono text-code">{entry.code}</span>
                  <StatusBadge tone={entry.active ? 'success' : 'neutral'}>
                    {entry.active ? tc('state.on') : tc('state.off')}
                  </StatusBadge>
                </span>
                <span className="flex items-center gap-2">
                  <span className="text-caption text-muted-foreground">
                    {entry.redeemedCount ?? 0}/{entry.maxRedemptions ?? '∞'}
                  </span>
                  <PermissionGate allowed={canManage && entry.active === true}>
                    <Button variant="ghost" size="sm" onClick={() => entry.id && deactivate.mutate({ id: entry.id })}>
                      {t('deactivate')}
                    </Button>
                  </PermissionGate>
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-small text-muted-foreground">{tc('state.none')}</p>
        )}
        <PermissionGate allowed={canManage}>
          <div className="grid grid-cols-2 gap-2">
            <Field label={t('code')} value={code} onChange={(event) => setCode(event.target.value)} mono />
            <Field
              label={t('maxRedemptions')}
              type="number"
              value={max}
              onChange={(event) => setMax(event.target.value)}
            />
          </div>
          <div className="flex justify-end">
            <Button size="sm" icon="plus" loading={add.isPending} onClick={() => add.mutate({ code, max })}>
              {t('addCode')}
            </Button>
          </div>
        </PermissionGate>
      </section>
    </div>
  )
}
