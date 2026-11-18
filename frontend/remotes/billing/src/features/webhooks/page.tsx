import { permissions } from '@suite/api-client'
import type { DeliveryResponse, EndpointResponse } from '@suite/api-client/billing'
import { createEndpoint, resend, rotateSecret, updateEndpoint } from '@suite/api-client/billing'
import { listDeliveriesOptions, listEndpointsOptions } from '@suite/api-client/billing/query'
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
  SecretDialog,
  Timestamp,
  useListState,
} from '../../lib/page'
import { useAction, useApi, useToast } from '../../lib/runtime'

const spec: ListSpec = {
  sortable: ['created_at', 'id'],
  facets: [
    {
      key: 'status',
      label: 'Status',
      field: 'status',
      options: [
        { value: 'ACTIVE', label: 'Active' },
        { value: 'DISABLED', label: 'Disabled' },
      ],
    },
  ],
}

export function WebhooksPage({ state }: { state: UrlState }) {
  const t = useT('webhooks')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.listEndpoints)
  const canManage = usePermission(permissions.billing.createEndpoint)
  const list = useListState(state, spec)

  const query = useQuery({
    ...listEndpointsOptions({
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
  const [form, setForm] = useState({ url: '', description: '', eventTypes: '' })
  const [secret, setSecret] = useState<string | null>(null)
  const [rotateOpen, setRotateOpen] = useState(false)

  const create = useAction(
    (vars: { url: string; description: string; eventTypes: string }) =>
      createEndpoint({
        client: billing,
        throwOnError: true,
        body: {
          url: vars.url.trim(),
          description: vars.description.trim() || undefined,
          eventTypes: vars.eventTypes
            .split(/[\n,]/)
            .map((entry) => entry.trim())
            .filter(Boolean),
        },
      }),
    {
      onSuccess: (result) => {
        setCreateOpen(false)
        setForm({ url: '', description: '', eventTypes: '' })
        setSecret(result.data.signingSecret ?? null)
        toast({ title: t('created'), tone: 'success' })
      },
    },
  )

  const setStatus = useAction(
    (vars: { id: string; status: string }) =>
      updateEndpoint({ client: billing, throwOnError: true, path: { id: vars.id }, body: { status: vars.status } }),
    { onSuccess: () => toast({ title: t('updated'), tone: 'success' }) },
  )

  const rotate = useAction(
    (vars: { id: string }) => rotateSecret({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: (result) => {
        setRotateOpen(false)
        setSecret(result.data.signingSecret ?? null)
        toast({ title: t('rotated'), tone: 'success' })
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
            key: 'url',
            header: t('url'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.url}</span>
                <span className="text-caption text-muted-foreground">{row.description}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          {
            key: 'event_types',
            header: t('eventTypes'),
            sortable: false,
            render: (row) => <span className="text-small">{row.eventTypes?.join(', ')}</span>,
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
          title={selected.url ?? ''}
          description={selected.description}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canManage}>
              <Button variant="outline" size="sm" icon="key" onClick={() => setRotateOpen(true)}>
                {t('rotate')}
              </Button>
              <Button
                variant="outline"
                size="sm"
                icon={selected.status === 'ACTIVE' ? 'ban' : 'check-circle'}
                onClick={() =>
                  selected.id &&
                  setStatus.mutate({
                    id: selected.id,
                    status: selected.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
                  })
                }
              >
                {selected.status === 'ACTIVE' ? tc('actions.disable') : tc('actions.enable')}
              </Button>
            </PermissionGate>
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={t('url')}>{selected.url}</DetailRow>
              <DetailRow label={tc('fields.status')}>
                <StatusBadge status={selected.status} />
              </DetailRow>
              <DetailRow label={t('eventTypes')}>{selected.eventTypes?.join(', ')}</DetailRow>
              <DetailRow label={tc('fields.created')}>
                <Timestamp value={selected.createdAt} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>
            <Deliveries endpoint={selected} canManage={canManage} />
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
          label={t('url')}
          type="url"
          value={form.url}
          onChange={(event) => setForm({ ...form, url: event.target.value })}
          required
        />
        <Field
          label={t('descriptionField')}
          value={form.description}
          onChange={(event) => setForm({ ...form, description: event.target.value })}
          optional
        />
        <Field
          label={t('eventTypes')}
          type="textarea"
          mono
          value={form.eventTypes}
          onChange={(event) => setForm({ ...form, eventTypes: event.target.value })}
        />
      </FormDialog>

      <ConfirmDialog
        open={rotateOpen}
        onClose={() => setRotateOpen(false)}
        title={tc('confirm.rotateSecret')}
        description={tc('confirm.rotateSecretHint')}
        confirmLabel={t('rotate')}
        busy={rotate.isPending}
        onConfirm={() => {
          if (selected?.id) rotate.mutate({ id: selected.id })
        }}
        impact={[{ label: t('url'), value: selected?.url ?? '' }]}
      />

      <SecretDialog open={Boolean(secret)} onClose={() => setSecret(null)} secret={secret} />
    </>
  )
}

function Deliveries({ endpoint, canManage }: { endpoint: EndpointResponse; canManage: boolean }) {
  const t = useT('webhooks')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const query = useQuery({
    ...listDeliveriesOptions({
      client: billing,
      throwOnError: true,
      query: { 'filter[endpoint_id][eq]': endpoint.id ?? '', limit: '50', page_info: 'full' } as never,
    }),
    enabled: Boolean(endpoint.id),
  })
  const [target, setTarget] = useState<DeliveryResponse | null>(null)
  const doResend = useAction(
    (vars: { id: string }) => resend({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setTarget(null)
        toast({ title: t('resend'), tone: 'success' })
      },
    },
  )
  const rows = query.data?.data ?? []
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-heading text-foreground">{t('deliveries')}</h2>
      {rows.length > 0 ? (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {rows.map((delivery) => (
            <li key={delivery.id} className="flex items-start justify-between gap-2 px-3 py-2 text-small">
              <span className="flex flex-col">
                <span className="flex items-center gap-2">
                  <StatusBadge status={delivery.status} />
                  <span className="text-foreground">{delivery.eventType}</span>
                </span>
                <span className="text-caption text-muted-foreground">
                  {t('attempts')} {delivery.attempts ?? 0} · {delivery.lastStatusCode ?? '—'}
                </span>
                <span className="text-caption text-muted-foreground">{delivery.lastError}</span>
              </span>
              <span className="flex items-center gap-2">
                <Timestamp value={delivery.createdAt} />
                <PermissionGate allowed={canManage}>
                  <Button variant="ghost" size="sm" onClick={() => setTarget(delivery)}>
                    {t('resend')}
                  </Button>
                </PermissionGate>
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-small text-muted-foreground">{t('emptyDeliveries')}</p>
      )}
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.resendDelivery')}
        description={tc('confirm.resendDeliveryHint')}
        confirmLabel={t('resend')}
        busy={doResend.isPending}
        onConfirm={() => {
          if (target?.id) doResend.mutate({ id: target.id })
        }}
        impact={[
          { label: t('delivery'), value: target?.id ?? '' },
          { label: t('eventTypes'), value: target?.eventType ?? '' },
        ]}
      />
    </section>
  )
}
