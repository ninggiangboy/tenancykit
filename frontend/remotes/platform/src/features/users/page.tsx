import { etag, permissions } from '@suite/api-client'
import { create, setPermission, update1 } from '@suite/api-client/platform'
import { listOptions } from '@suite/api-client/platform/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, DropdownMenu, Field, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
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
  sortable: ['id'],
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
        { value: 'DISABLED', label: 'Disabled' },
      ],
    },
    {
      key: 'scope',
      label: 'Scope',
      field: 'scope',
      options: [
        { value: 'PLATFORM', label: 'Platform' },
        { value: 'TENANT', label: 'Tenant' },
      ],
    },
  ],
}

interface CreateForm {
  tenantCode: string
  displayName: string
  email: string
  temporaryPassword: string
}

const emptyForm: CreateForm = { tenantCode: '', displayName: '', email: '', temporaryPassword: '' }

export function UsersPage({ state }: { state: UrlState }) {
  const t = useT('users')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const canRead = usePermission(permissions.platform.list)
  const canManage = usePermission(permissions.platform.update_1)
  const canGrant = usePermission(permissions.platform.setPermission)
  const canCreate = usePermission(permissions.platform.create)
  const queryClient = useQueryClient()
  const list = useListState(state, spec)

  const query = useQuery({
    ...listOptions({ client: platform, throwOnError: true, query: asListQuery<Record<string, string>>(list.params) }),
    enabled: canRead,
  })
  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState<CreateForm>(emptyForm)
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameValue, setRenameValue] = useState('')
  const [code, setCode] = useState('')

  const undoChange = async (id: string, action: 'ENABLE' | 'DISABLE', version: number) => {
    await update1({
      client: platform,
      throwOnError: true,
      path: { id },
      body: { action },
      headers: { 'If-Match': etag(version) },
    })
    await queryClient.invalidateQueries()
  }

  const change = useAction(
    (vars: { id: string; action: 'ENABLE' | 'DISABLE' | 'RENAME'; name?: string; version: number }) =>
      update1({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { action: vars.action, name: vars.name },
        headers: { 'If-Match': etag(vars.version) },
      }),
    {
      onSuccess: (_result, vars) => {
        if (vars.action === 'RENAME') {
          setRenameOpen(false)
          toast({ title: t('renamed'), tone: 'success' })
          return
        }
        const enabled = vars.action === 'ENABLE'
        toast({
          title: enabled ? t('enabled') : t('disabled'),
          tone: 'success',
          action: {
            label: tc('actions.undo'),
            onClick: () => void undoChange(vars.id, enabled ? 'DISABLE' : 'ENABLE', vars.version),
          },
        })
      },
    },
  )

  const createUser = useAction(
    (vars: CreateForm) =>
      create({
        client: platform,
        throwOnError: true,
        body: {
          tenantCode: vars.tenantCode.trim() || undefined,
          displayName: vars.displayName.trim(),
          email: vars.email.trim(),
          temporaryPassword: vars.temporaryPassword,
        },
      }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setForm(emptyForm)
        toast({ title: t('created'), tone: 'success' })
      },
    },
  )

  const grantPermission = useAction(
    (vars: { id: string; code: string; grant: boolean; version: number }) =>
      setPermission({
        client: platform,
        throwOnError: true,
        path: { id: vars.id, code: vars.code },
        query: { grant: vars.grant },
        headers: { 'If-Match': etag(vars.version) },
      }),
    {
      onSuccess: (_result, vars) => {
        setCode('')
        toast({ title: vars.grant ? t('granted') : t('revoked'), tone: 'success' })
      },
    },
  )

  const primary = (
    <PermissionGate allowed={canCreate}>
      <Button icon="plus" onClick={() => setCreateOpen(true)}>
        {t('create')}
      </Button>
    </PermissionGate>
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
        primary={primary}
        caption={t('title')}
        emptyTitle={t('emptyTitle')}
        emptyHint={t('emptyHint')}
        columns={[
          {
            key: 'display_name',
            header: tc('fields.displayName'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.displayName}</span>
                <span className="text-caption text-muted-foreground">{row.email}</span>
              </span>
            ),
          },
          {
            key: 'scope',
            header: tc('fields.scope'),
            render: (row) => <StatusBadge tone="neutral">{row.scope}</StatusBadge>,
          },
          {
            key: 'status',
            header: tc('fields.status'),
            render: (row) => <StatusBadge status={row.status} />,
          },
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
            <PermissionGate allowed={canManage}>
              <DropdownMenu
                label={tc('fields.actions')}
                items={[
                  {
                    label: tc('actions.rename'),
                    icon: 'settings',
                    onSelect: () => {
                      setRenameValue(selected.displayName ?? '')
                      setRenameOpen(true)
                    },
                  },
                  {
                    label: selected.status === 'ACTIVE' ? t('disable') : t('enable'),
                    icon: selected.status === 'ACTIVE' ? 'ban' : 'check-circle',
                    danger: selected.status === 'ACTIVE',
                    onSelect: () => {
                      if (selected.id && selected.version !== undefined) {
                        change.mutate({
                          id: selected.id,
                          action: selected.status === 'ACTIVE' ? 'DISABLE' : 'ENABLE',
                          version: selected.version,
                        })
                      }
                    },
                  },
                ]}
              />
            </PermissionGate>
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.email')}>{selected.email}</DetailRow>
              <DetailRow label={tc('fields.scope')}>{selected.scope}</DetailRow>
              <DetailRow label={tc('fields.tenant')}>{selected.tenantId ?? tc('state.none')}</DetailRow>
              <DetailRow label={tc('fields.status')}>
                <StatusBadge status={selected.status} />
              </DetailRow>
              <DetailRow label={tc('fields.version')}>{selected.version}</DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>

            <PermissionGate allowed={canGrant}>
              <section className="flex flex-col gap-2">
                <div className="flex flex-col gap-0.5">
                  <h2 className="text-heading text-foreground">{t('permissionsTitle')}</h2>
                  <p className="text-small text-muted-foreground">{t('permissionsDescription')}</p>
                </div>
                <Field
                  label={t('code')}
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  placeholder="billing:invoice:read"
                  mono
                />
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    icon="check"
                    disabled={!code.trim() || selected.version === undefined}
                    loading={grantPermission.isPending}
                    onClick={() => {
                      if (selected.id && selected.version !== undefined) {
                        grantPermission.mutate({
                          id: selected.id,
                          code: code.trim(),
                          grant: true,
                          version: selected.version,
                        })
                      }
                    }}
                  >
                    {t('grant')}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    icon="x"
                    disabled={!code.trim() || selected.version === undefined}
                    onClick={() => {
                      if (selected.id && selected.version !== undefined) {
                        grantPermission.mutate({
                          id: selected.id,
                          code: code.trim(),
                          grant: false,
                          version: selected.version,
                        })
                      }
                    }}
                  >
                    {t('revoke')}
                  </Button>
                </div>
              </section>
            </PermissionGate>
          </div>
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={tc('actions.create')}
        busy={createUser.isPending}
        onSubmit={() => createUser.mutate(form)}
      >
        <Field
          label={t('tenantCode')}
          value={form.tenantCode}
          onChange={(event) => setForm({ ...form, tenantCode: event.target.value })}
          optional
        />
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
          required
        />
        <Field
          label={t('temporaryPassword')}
          type="password"
          value={form.temporaryPassword}
          onChange={(event) => setForm({ ...form, temporaryPassword: event.target.value })}
          required
        />
      </FormDialog>

      <FormDialog
        open={renameOpen}
        onClose={() => setRenameOpen(false)}
        title={t('renameTitle')}
        submitLabel={tc('actions.save')}
        busy={change.isPending}
        onSubmit={() => {
          if (selected?.id && selected.version !== undefined) {
            change.mutate({ id: selected.id, action: 'RENAME', name: renameValue, version: selected.version })
          }
        }}
      >
        <Field
          label={tc('fields.displayName')}
          value={renameValue}
          onChange={(event) => setRenameValue(event.target.value)}
        />
      </FormDialog>
    </>
  )
}
