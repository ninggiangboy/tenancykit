import { permissions } from '@suite/api-client'
import { create, deactivate, update } from '@suite/api-client/billing'
import { list1Options } from '@suite/api-client/billing/query'
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
  sortable: ['display_name', 'created_at', 'id'],
  searchField: 'display_name',
  searchOp: 'starts_with',
  searchPlaceholder: 'Search by display name',
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
  displayName: string
  email: string
  tenantSystemId: string
  externalUserId: string
}

const empty: Form = { displayName: '', email: '', tenantSystemId: '', externalUserId: '' }

export function CustomersPage({ state }: { state: UrlState }) {
  const t = useT('customers')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.list_1)
  const canWrite = usePermission(permissions.billing.create)
  const list = useListState(state, spec)

  const query = useQuery({
    ...list1Options({ client: billing, throwOnError: true, query: asListQuery<Record<string, string>>(list.params) }),
    enabled: canRead,
  })
  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState<Form>(empty)
  const [editOpen, setEditOpen] = useState(false)
  const [edit, setEdit] = useState<Form>(empty)
  const [deactivateOpen, setDeactivateOpen] = useState(false)

  const createCustomer = useAction(
    (vars: Form) =>
      create({
        client: billing,
        throwOnError: true,
        headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
        body: {
          displayName: vars.displayName.trim(),
          email: vars.email.trim() || undefined,
          tenantSystemId: vars.tenantSystemId.trim() || undefined,
          externalUserId: vars.externalUserId.trim() || undefined,
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

  const updateCustomer = useAction(
    (vars: { id: string; displayName: string; email: string }) =>
      update({
        client: billing,
        throwOnError: true,
        path: { id: vars.id },
        body: { displayName: vars.displayName.trim(), email: vars.email.trim() },
      }),
    {
      onSuccess: () => {
        setEditOpen(false)
        toast({ title: t('updated'), tone: 'success' })
      },
    },
  )

  const deactivateCustomer = useAction(
    (vars: { id: string }) => deactivate({ client: billing, throwOnError: true, path: { id: vars.id } }),
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
          <PermissionGate allowed={canWrite}>
            <Button icon="plus" onClick={() => setCreateOpen(true)}>
              {t('create')}
            </Button>
          </PermissionGate>
        }
        columns={[
          {
            key: 'display_name',
            header: tc('fields.customer'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.displayName}</span>
                <span className="text-caption text-muted-foreground">{row.email ?? tc('state.none')}</span>
              </span>
            ),
          },
          { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
          { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
          {
            key: 'id',
            header: tc('fields.id'),
            sortable: false,
            render: (row) => <IdValue value={row.id} />,
          },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={selected.displayName ?? ''}
          description={selected.email}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <>
              <PermissionGate allowed={canWrite}>
                <Button
                  variant="outline"
                  size="sm"
                  icon="settings"
                  onClick={() => {
                    setEdit({
                      displayName: selected.displayName ?? '',
                      email: selected.email ?? '',
                      tenantSystemId: '',
                      externalUserId: '',
                    })
                    setEditOpen(true)
                  }}
                >
                  {tc('actions.edit')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canWrite}>
                <Button variant="destructive" size="sm" icon="ban" onClick={() => setDeactivateOpen(true)}>
                  {t('deactivate')}
                </Button>
              </PermissionGate>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.email')}>{selected.email ?? tc('state.none')}</DetailRow>
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
              <h2 className="text-heading text-foreground">{t('identities')}</h2>
              {selected.identities && selected.identities.length > 0 ? (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {selected.identities.map((identity) => (
                    <li key={`${identity.tenantSystemId}:${identity.externalUserId}`} className="px-3 py-2">
                      <div className="flex justify-between gap-2 text-small">
                        <span className="text-muted-foreground">{t('identitySystem')}</span>
                        <span className="font-mono text-code">{identity.tenantSystemId}</span>
                      </div>
                      <div className="flex justify-between gap-2 text-small">
                        <span className="text-muted-foreground">{t('identityUser')}</span>
                        <span className="font-mono text-code">{identity.externalUserId}</span>
                      </div>
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
        busy={createCustomer.isPending}
        onSubmit={() => createCustomer.mutate(form)}
      >
        <Field
          label={tc('fields.displayName')}
          value={form.displayName}
          onChange={(event) => setForm({ ...form, displayName: event.target.value })}
          required
        />
        <Field
          label={tc('fields.email')}
          type="email"
          value={form.email}
          onChange={(event) => setForm({ ...form, email: event.target.value })}
        />
        <Field
          label={t('identitySystem')}
          value={form.tenantSystemId}
          onChange={(event) => setForm({ ...form, tenantSystemId: event.target.value })}
          mono
          optional
        />
        <Field
          label={t('identityUser')}
          value={form.externalUserId}
          onChange={(event) => setForm({ ...form, externalUserId: event.target.value })}
          mono
          optional
        />
      </FormDialog>

      <FormDialog
        open={editOpen}
        onClose={() => setEditOpen(false)}
        title={tc('actions.edit')}
        submitLabel={tc('actions.save')}
        busy={updateCustomer.isPending}
        onSubmit={() => {
          if (selected?.id) {
            updateCustomer.mutate({ id: selected.id, displayName: edit.displayName, email: edit.email })
          }
        }}
      >
        <Field
          label={tc('fields.displayName')}
          value={edit.displayName}
          onChange={(event) => setEdit({ ...edit, displayName: event.target.value })}
        />
        <Field
          label={tc('fields.email')}
          type="email"
          value={edit.email}
          onChange={(event) => setEdit({ ...edit, email: event.target.value })}
        />
      </FormDialog>

      <ConfirmDialog
        open={deactivateOpen}
        onClose={() => setDeactivateOpen(false)}
        title={tc('confirm.deactivateCustomer')}
        description={tc('confirm.deactivateCustomerHint')}
        confirmLabel={t('deactivate')}
        busy={deactivateCustomer.isPending}
        onConfirm={() => {
          if (selected?.id) {
            deactivateCustomer.mutate({ id: selected.id })
          }
        }}
        impact={[
          { label: tc('fields.customer'), value: selected?.displayName ?? '' },
          { label: tc('fields.email'), value: selected?.email ?? '' },
        ]}
      />
    </>
  )
}
