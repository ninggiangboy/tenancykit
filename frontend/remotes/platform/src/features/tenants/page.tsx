import { etag, permissions } from '@suite/api-client'
import type { TenantResponse } from '@suite/api-client/platform'
import { addDomain, create1, deactivate, rename, set } from '@suite/api-client/platform'
import { appsOptions, list1Options } from '@suite/api-client/platform/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Alert, AppMark, Button, Field, humanize, PermissionGate, StatusBadge, Switch, type UrlState } from '@suite/ui'
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
import { errorMessage, useAction, useApi, useToast } from '../../lib/runtime'

const spec: ListSpec = {
  sortable: ['name', 'created_at', 'id'],
  searchField: 'code',
  searchOp: 'starts_with',
  searchPlaceholder: 'Search by tenant code',
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

interface TenantFormState {
  code: string
  name: string
  adminEmail: string
  adminTemporaryPassword: string
}

const emptyForm: TenantFormState = { code: '', name: '', adminEmail: '', adminTemporaryPassword: '' }

export function TenantsPage({ state }: { state: UrlState }) {
  const t = useT('tenants')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const canRead = usePermission(permissions.platform.list_1)
  const canRename = usePermission(permissions.platform.rename)
  const canDeactivate = usePermission(permissions.platform.deactivate)
  const canAddDomain = usePermission(permissions.platform.addDomain)
  const canManageApps = usePermission(permissions.platform.set)
  const canCreate = usePermission(permissions.platform.create_1)
  const list = useListState(state, spec)

  const query = useQuery({
    ...list1Options({ client: platform, throwOnError: true, query: asListQuery<Record<string, string>>(list.params) }),
    enabled: canRead,
  })

  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState<TenantFormState>(emptyForm)
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameValue, setRenameValue] = useState('')
  const [deactivateOpen, setDeactivateOpen] = useState(false)
  const [typedCode, setTypedCode] = useState('')
  const [domainOpen, setDomainOpen] = useState(false)
  const [hostname, setHostname] = useState('')

  const createTenant = useAction(
    (vars: TenantFormState) =>
      create1({
        client: platform,
        throwOnError: true,
        body: {
          code: vars.code.trim(),
          name: vars.name.trim(),
          adminEmail: vars.adminEmail.trim(),
          adminTemporaryPassword: vars.adminTemporaryPassword,
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

  const renameTenant = useAction(
    (vars: { id: string; name: string; version: number }) =>
      rename({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { name: vars.name.trim() },
        headers: { 'If-Match': etag(vars.version) },
      }),
    {
      onSuccess: () => {
        setRenameOpen(false)
        toast({ title: t('renamed'), tone: 'success' })
      },
    },
  )

  const deactivateTenant = useAction(
    (vars: { id: string; version: number }) =>
      deactivate({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        headers: { 'If-Match': etag(vars.version) },
      }),
    {
      onSuccess: () => {
        setDeactivateOpen(false)
        setTypedCode('')
        toast({ title: t('deactivated'), tone: 'success' })
      },
    },
  )

  const addTenantDomain = useAction(
    (vars: { id: string; hostname: string }) =>
      addDomain({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { hostname: vars.hostname.trim() },
      }),
    {
      onSuccess: () => {
        setDomainOpen(false)
        setHostname('')
        toast({ title: t('domainAdded'), tone: 'success' })
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
        rowKey={(row) => row.id ?? row.code ?? ''}
        primary={primary}
        caption={t('title')}
        emptyTitle={t('emptyTitle')}
        emptyHint={t('emptyHint')}
        columns={[
          {
            key: 'name',
            header: tc('fields.name'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.name}</span>
                <span className="font-mono text-caption text-muted-foreground">{row.code}</span>
              </span>
            ),
          },
          {
            key: 'status',
            header: tc('fields.status'),
            render: (row) => <StatusBadge status={row.status} />,
          },
          {
            key: 'created_at',
            header: tc('fields.created'),
            render: (row) => <Timestamp value={row.createdAt} />,
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
          title={selected.name ?? selected.code ?? ''}
          description={selected.code}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <>
              <PermissionGate allowed={canRename}>
                <Button
                  variant="outline"
                  size="sm"
                  icon="settings"
                  onClick={() => {
                    setRenameValue(selected.name ?? '')
                    setRenameOpen(true)
                  }}
                >
                  {tc('actions.rename')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canAddDomain}>
                <Button variant="outline" size="sm" icon="plus" onClick={() => setDomainOpen(true)}>
                  {tc('actions.addDomain')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canDeactivate}>
                <Button variant="destructive" size="sm" icon="ban" onClick={() => setDeactivateOpen(true)}>
                  {tc('actions.deactivate')}
                </Button>
              </PermissionGate>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.code')}>
                <span className="font-mono text-code">{selected.code}</span>
              </DetailRow>
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
            <TenantApps tenant={selected} canManage={canManageApps} />
          </div>
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={t('create')}
        busy={createTenant.isPending}
        onSubmit={() => createTenant.mutate(form)}
      >
        <Field
          label={tc('fields.code')}
          value={form.code}
          onChange={(event) => setForm({ ...form, code: event.target.value })}
          required
        />
        <Field
          label={tc('fields.name')}
          value={form.name}
          onChange={(event) => setForm({ ...form, name: event.target.value })}
          required
        />
        <Field
          label={t('adminEmail')}
          type="email"
          value={form.adminEmail}
          onChange={(event) => setForm({ ...form, adminEmail: event.target.value })}
          required
        />
        <Field
          label={tc('fields.temporaryPassword')}
          type="password"
          value={form.adminTemporaryPassword}
          onChange={(event) => setForm({ ...form, adminTemporaryPassword: event.target.value })}
          required
        />
      </FormDialog>

      <FormDialog
        open={renameOpen}
        onClose={() => setRenameOpen(false)}
        title={tc('actions.rename')}
        submitLabel={tc('actions.save')}
        busy={renameTenant.isPending}
        onSubmit={() => {
          if (selected?.id && selected.version !== undefined) {
            renameTenant.mutate({ id: selected.id, name: renameValue, version: selected.version })
          }
        }}
      >
        <Field label={tc('fields.name')} value={renameValue} onChange={(event) => setRenameValue(event.target.value)} />
      </FormDialog>

      <FormDialog
        open={domainOpen}
        onClose={() => setDomainOpen(false)}
        title={t('domainTitle')}
        description={t('domainHint')}
        submitLabel={tc('actions.addDomain')}
        busy={addTenantDomain.isPending}
        onSubmit={() => {
          if (selected?.id) addTenantDomain.mutate({ id: selected.id, hostname })
        }}
      >
        <Field
          label={tc('fields.hostname')}
          value={hostname}
          onChange={(event) => setHostname(event.target.value)}
          placeholder="acme.example.com"
          required
        />
        <Alert tone="info" title={t('domainTitle')}>
          {t('domainHint')}
        </Alert>
      </FormDialog>

      <ConfirmDialog
        open={deactivateOpen}
        onClose={() => {
          setDeactivateOpen(false)
          setTypedCode('')
        }}
        title={tc('confirm.deactivateTenant')}
        description={tc('confirm.deactivateTenantHint')}
        confirmLabel={tc('actions.deactivate')}
        busy={deactivateTenant.isPending}
        typed={{
          label: tc('confirm.typeCode'),
          expected: selected?.code ?? '',
          value: typedCode,
          onChange: setTypedCode,
        }}
        impact={[
          { label: t('apps'), value: t('noApps') },
          { label: tc('fields.tenant'), value: selected?.code ?? '' },
        ]}
        onConfirm={() => {
          if (selected?.id && selected.version !== undefined) {
            deactivateTenant.mutate({ id: selected.id, version: selected.version })
          }
        }}
      />
    </>
  )
}

function TenantApps({ tenant, canManage }: { tenant: TenantResponse; canManage: boolean }) {
  const t = useT('tenants')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const [optimistic, setOptimistic] = useState<Record<string, boolean>>({})
  const query = useQuery({
    ...appsOptions({ client: platform, throwOnError: true, path: { tenantId: tenant.id ?? '' } }),
    enabled: Boolean(tenant.id),
  })
  const apps = query.data ?? []
  const save = useAction(
    (vars: { appCode: string; enabled: boolean; version: number }) =>
      set({
        client: platform,
        throwOnError: true,
        path: { tenantId: tenant.id ?? '', appCode: vars.appCode },
        body: { enabled: vars.enabled },
        headers: { 'If-Match': etag(vars.version) },
      }),
    {
      onSuccess: (_result, vars) => {
        setOptimistic((current) => {
          const next = { ...current }
          delete next[vars.appCode]
          return next
        })
        toast({
          title: t(vars.enabled ? 'appEnabled' : 'appDisabled', {
            app: humanize(vars.appCode.toUpperCase()),
            tenant: tenant.code ?? '',
          }),
          tone: 'success',
        })
      },
      onError: (_error, vars) => {
        setOptimistic((current) => {
          const next = { ...current }
          delete next[vars.appCode]
          return next
        })
      },
    },
  )

  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-col gap-0.5">
        <h2 className="text-heading text-foreground">{t('appsTitle')}</h2>
        <p className="text-small text-muted-foreground">{t('appsDescription')}</p>
      </div>
      {query.error ? (
        <Alert tone="danger" title={tc('state.error')}>
          {errorMessage(query.error)}
        </Alert>
      ) : apps.length === 0 ? (
        <p className="text-small text-muted-foreground">{t('noApps')}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {apps.map((app) => {
            const appCode = app.appCode ?? ''
            const appLabel = humanize(appCode.toUpperCase())
            const enabled = optimistic[appCode] ?? app.enabled ?? false
            return (
              <li key={appCode} className="flex items-center justify-between gap-3 px-3 py-2.5">
                <span className="flex items-center gap-2">
                  <AppMark app={appCode} size="sm" />
                  <span className="flex flex-col">
                    <span className="text-body-strong text-foreground">{appLabel}</span>
                    <span className="font-mono text-caption text-muted-foreground">{appCode}</span>
                  </span>
                </span>
                <span className="flex items-center gap-3">
                  <PermissionGate allowed={canManage}>
                    <Switch
                      aria-label={appLabel}
                      checked={enabled}
                      busy={save.isPending && save.variables?.appCode === appCode}
                      onCheckedChange={(checked) => {
                        setOptimistic((current) => ({ ...current, [appCode]: checked }))
                        if (app.version !== undefined) {
                          save.mutate({ appCode, enabled: checked, version: app.version })
                        }
                      }}
                    />
                  </PermissionGate>
                  <StatusBadge tone={enabled ? 'success' : 'neutral'}>
                    {enabled ? tc('state.on') : tc('state.off')}
                  </StatusBadge>
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
