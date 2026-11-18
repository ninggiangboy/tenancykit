import { permissions } from '@suite/api-client'
import { createPlan, publishVersion, retire } from '@suite/api-client/billing'
import { listPlansOptions } from '@suite/api-client/billing/query'
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
  sortable: ['code', 'id'],
  searchField: 'code',
  searchOp: 'starts_with',
  searchPlaceholder: 'Search by plan code',
  facets: [
    {
      key: 'status',
      label: 'Status',
      field: 'status',
      options: [
        { value: 'ACTIVE', label: 'Active' },
        { value: 'RETIRED', label: 'Retired' },
      ],
    },
  ],
}

interface PlanForm {
  code: string
  name: string
  currency: string
  model: string
  billingCycle: string
  metric: string
  unitPrice: string
}

const empty: PlanForm = {
  code: '',
  name: '',
  currency: 'USD',
  model: 'FLAT',
  billingCycle: 'MONTHLY',
  metric: '',
  unitPrice: '',
}

export function PlansPage({ state }: { state: UrlState }) {
  const t = useT('plans')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.listPlans)
  const canManage = usePermission(permissions.billing.createPlan)
  const list = useListState(state, spec)

  const query = useQuery({
    ...listPlansOptions({
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
  const [form, setForm] = useState<PlanForm>(empty)
  const [versionOpen, setVersionOpen] = useState(false)
  const [version, setVersion] = useState({ billingCycle: 'MONTHLY', metric: '', unitPrice: '' })
  const [retireOpen, setRetireOpen] = useState(false)

  const create = useAction(
    (vars: PlanForm) =>
      createPlan({
        client: billing,
        throwOnError: true,
        body: {
          code: vars.code.trim(),
          name: vars.name.trim(),
          currency: vars.currency.trim().toUpperCase(),
          model: vars.model.trim(),
          version: {
            billingCycle: vars.billingCycle.trim(),
            rule: {
              model: vars.model.trim(),
              metric: vars.metric.trim() || undefined,
              unitPrice: vars.unitPrice.trim() || undefined,
            },
          },
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

  const publish = useAction(
    (vars: { id: string; billingCycle: string; metric: string; unitPrice: string }) =>
      publishVersion({
        client: billing,
        throwOnError: true,
        path: { id: vars.id },
        body: {
          billingCycle: vars.billingCycle.trim(),
          rule: {
            model: selected?.model ?? form.model.trim(),
            metric: vars.metric.trim() || undefined,
            unitPrice: vars.unitPrice.trim() || undefined,
          },
        },
      }),
    {
      onSuccess: () => {
        setVersionOpen(false)
        toast({ title: t('versionPublished'), tone: 'success' })
      },
    },
  )

  const retirePlan = useAction(
    (vars: { id: string }) => retire({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setRetireOpen(false)
        toast({ title: t('retired'), tone: 'success' })
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
            key: 'code',
            header: t('code'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.name}</span>
                <span className="font-mono text-caption text-muted-foreground">{row.code}</span>
              </span>
            ),
          },
          { key: 'model', header: t('model'), render: (row) => <span>{row.model}</span> },
          { key: 'currency', header: tc('fields.currency'), render: (row) => <span>{row.currency}</span> },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'versions',
            header: t('versions'),
            sortable: false,
            render: (row) => <span>{row.versions?.length ?? 0}</span>,
          },
          { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={selected.name ?? ''}
          description={selected.code}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <>
              <PermissionGate allowed={canManage}>
                <Button
                  variant="outline"
                  size="sm"
                  icon="plus"
                  onClick={() => {
                    setVersion({
                      billingCycle: selected.versions?.[0]?.billingCycle ?? 'MONTHLY',
                      metric: selected.versions?.[0]?.rule?.metric ?? '',
                      unitPrice: selected.versions?.[0]?.rule?.unitPrice ?? '',
                    })
                    setVersionOpen(true)
                  }}
                >
                  {t('publish')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canManage && selected.status !== 'RETIRED'}>
                <Button variant="destructive" size="sm" icon="ban" onClick={() => setRetireOpen(true)}>
                  {t('retire')}
                </Button>
              </PermissionGate>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={t('model')}>{selected.model}</DetailRow>
              <DetailRow label={tc('fields.currency')}>{selected.currency}</DetailRow>
              <DetailRow label={tc('fields.status')}>
                <StatusBadge status={selected.status} />
              </DetailRow>
              <DetailRow label={tc('fields.created')}>
                <Timestamp value={selected.createdAt} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>
            <section className="flex flex-col gap-2">
              <h2 className="text-heading text-foreground">{t('versions')}</h2>
              {selected.versions && selected.versions.length > 0 ? (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {selected.versions.map((entry) => (
                    <li key={entry.version} className="flex items-start justify-between gap-2 px-3 py-2 text-small">
                      <span className="flex flex-col">
                        <span className="font-mono text-code text-foreground">
                          {t('version')} {entry.version}
                        </span>
                        <span className="text-caption text-muted-foreground">
                          {entry.billingCycle} · {entry.rule?.model} · {entry.rule?.metric ?? tc('state.none')}
                        </span>
                        <span className="text-caption text-muted-foreground">
                          {t('unitPrice')} {entry.rule?.unitPrice ?? tc('state.none')}
                        </span>
                      </span>
                      <Timestamp value={entry.createdAt} />
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-small text-muted-foreground">{tc('state.none')}</p>
              )}
            </section>
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
          label={t('code')}
          value={form.code}
          onChange={(event) => setForm({ ...form, code: event.target.value })}
          mono
          required
        />
        <Field
          label={tc('fields.name')}
          value={form.name}
          onChange={(event) => setForm({ ...form, name: event.target.value })}
          required
        />
        <Field
          label={tc('fields.currency')}
          value={form.currency}
          onChange={(event) => setForm({ ...form, currency: event.target.value })}
          mono
        />
        <Field
          label={t('model')}
          value={form.model}
          onChange={(event) => setForm({ ...form, model: event.target.value })}
          mono
        />
        <Field
          label={t('billingCycle')}
          value={form.billingCycle}
          onChange={(event) => setForm({ ...form, billingCycle: event.target.value })}
        />
        <Field
          label={t('metric')}
          value={form.metric}
          onChange={(event) => setForm({ ...form, metric: event.target.value })}
          mono
        />
        <Field
          label={t('unitPrice')}
          value={form.unitPrice}
          onChange={(event) => setForm({ ...form, unitPrice: event.target.value })}
          mono
        />
      </FormDialog>

      <FormDialog
        open={versionOpen}
        onClose={() => setVersionOpen(false)}
        title={t('publish')}
        description={t('createHint')}
        submitLabel={t('publish')}
        busy={publish.isPending}
        onSubmit={() => {
          if (selected?.id) publish.mutate({ id: selected.id, ...version })
        }}
      >
        <Field
          label={t('billingCycle')}
          value={version.billingCycle}
          onChange={(event) => setVersion({ ...version, billingCycle: event.target.value })}
        />
        <Field
          label={t('metric')}
          value={version.metric}
          onChange={(event) => setVersion({ ...version, metric: event.target.value })}
          mono
        />
        <Field
          label={t('unitPrice')}
          value={version.unitPrice}
          onChange={(event) => setVersion({ ...version, unitPrice: event.target.value })}
          mono
        />
      </FormDialog>

      <ConfirmDialog
        open={retireOpen}
        onClose={() => setRetireOpen(false)}
        title={tc('confirm.retirePlan')}
        description={tc('confirm.retirePlanHint')}
        confirmLabel={t('retire')}
        busy={retirePlan.isPending}
        onConfirm={() => {
          if (selected?.id) retirePlan.mutate({ id: selected.id })
        }}
        impact={[
          { label: tc('fields.plan'), value: selected?.name ?? '' },
          { label: t('versions'), value: selected?.versions?.length ?? 0 },
        ]}
      />
    </>
  )
}
