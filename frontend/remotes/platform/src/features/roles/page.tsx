import { etag, permissions } from '@suite/api-client'
import type { RoleResponse } from '@suite/api-client/platform'
import { assign, create3, rename1, replacePermissions, unassign } from '@suite/api-client/platform'
import { list3Options } from '@suite/api-client/platform/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, Field, PermissionGate, StatusBadge, UnsavedBar, type UrlState, useHotkey } from '@suite/ui'
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
  sortable: ['name', 'id'],
  searchField: 'name',
  searchOp: 'contains',
  searchPlaceholder: 'Search by role name',
  facets: [
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

export function RolesPage({ state }: { state: UrlState }) {
  const t = useT('roles')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const queryClient = useQueryClient()
  const canRead = usePermission(permissions.platform.list_3)
  const canManage = usePermission(permissions.platform.create_3)
  const canCreate = usePermission(permissions.platform.create_3)
  const list = useListState(state, spec)

  const query = useQuery({
    ...list3Options({ client: platform, throwOnError: true, query: asListQuery<Record<string, string>>(list.params) }),
    enabled: canRead,
  })
  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [codes, setCodes] = useState('')
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameValue, setRenameValue] = useState('')

  const createRole = useAction(
    (vars: { name: string; permissions: string[] }) =>
      create3({
        client: platform,
        throwOnError: true,
        body: { name: vars.name.trim(), permissions: vars.permissions },
      }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setName('')
        setCodes('')
        toast({ title: t('created'), tone: 'success' })
      },
    },
  )

  const renameRole = useAction(
    (vars: { id: string; name: string; version: number }) =>
      rename1({
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

  const setPermissions = useAction(
    (vars: { id: string; permissions: string[]; version: number }) =>
      replacePermissions({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { permissions: vars.permissions },
        headers: { 'If-Match': etag(vars.version) },
      }),
    { onSuccess: () => toast({ title: t('saved'), tone: 'success' }) },
  )

  const assignUser = useAction(
    (vars: { id: string; userId: string; assign: boolean }) =>
      vars.assign
        ? assign({ client: platform, throwOnError: true, path: { id: vars.id, userId: vars.userId } })
        : unassign({ client: platform, throwOnError: true, path: { id: vars.id, userId: vars.userId } }),
    {
      onSuccess: (_result, vars) => {
        toast({
          title: vars.assign ? t('assigned') : t('unassigned'),
          tone: 'success',
          action: {
            label: tc('actions.undo'),
            onClick: async () => {
              if (vars.assign) {
                await unassign({ client: platform, throwOnError: true, path: { id: vars.id, userId: vars.userId } })
              } else {
                await assign({ client: platform, throwOnError: true, path: { id: vars.id, userId: vars.userId } })
              }
              await queryClient.invalidateQueries()
            },
          },
        })
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
            key: 'name',
            header: t('name'),
            render: (row) => (
              <span className="flex items-center gap-2">
                <span className="text-body-strong text-foreground">{row.name}</span>
                {row.system && <StatusBadge tone="neutral">{t('system')}</StatusBadge>}
              </span>
            ),
          },
          {
            key: 'scope',
            header: tc('fields.scope'),
            render: (row) => <StatusBadge tone="neutral">{row.scope}</StatusBadge>,
          },
          {
            key: 'permissions',
            header: tc('fields.permissions'),
            sortable: false,
            render: (row) => <span>{t('permissionCount', { count: row.permissions?.length ?? 0 })}</span>,
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
          title={selected.name ?? ''}
          description={selected.tenantId ?? tc('state.none')}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.id ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1 ? () => state.set({ open: rows[selectedIndex + 1]?.id ?? null }) : undefined
          }
          footer={
            <PermissionGate allowed={canManage}>
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
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.scope')}>{selected.scope}</DetailRow>
              <DetailRow label={tc('fields.tenant')}>{selected.tenantId ?? tc('state.none')}</DetailRow>
              <DetailRow label={tc('fields.permissions')}>{selected.permissions?.length ?? 0}</DetailRow>
              <DetailRow label={tc('fields.version')}>{selected.version}</DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>

            <RolePermissions
              key={selected.id}
              role={selected}
              canManage={canManage}
              onSave={(permissionCodes) =>
                selected.id && selected.version !== undefined
                  ? setPermissions.mutate({
                      id: selected.id,
                      permissions: permissionCodes,
                      version: selected.version,
                    })
                  : undefined
              }
              saving={setPermissions.isPending}
            />

            <PermissionGate allowed={canManage}>
              <AssignUser
                onAssign={(userId, isAssign) =>
                  selected.id ? assignUser.mutate({ id: selected.id, userId, assign: isAssign }) : undefined
                }
                busy={assignUser.isPending}
              />
            </PermissionGate>
          </div>
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={t('create')}
        busy={createRole.isPending}
        onSubmit={() =>
          createRole.mutate({
            name,
            permissions: codes
              .split(/[\n,]/)
              .map((entry) => entry.trim())
              .filter(Boolean),
          })
        }
      >
        <Field label={t('name')} value={name} onChange={(event) => setName(event.target.value)} required />
        <Field
          label={tc('fields.permissions')}
          type="textarea"
          mono
          value={codes}
          onChange={(event) => setCodes(event.target.value)}
          hint={t('codesHint')}
        />
      </FormDialog>

      <FormDialog
        open={renameOpen}
        onClose={() => setRenameOpen(false)}
        title={t('renameTitle')}
        submitLabel={tc('actions.save')}
        busy={renameRole.isPending}
        onSubmit={() => {
          if (selected?.id && selected.version !== undefined) {
            renameRole.mutate({ id: selected.id, name: renameValue, version: selected.version })
          }
        }}
      >
        <Field label={t('name')} value={renameValue} onChange={(event) => setRenameValue(event.target.value)} />
      </FormDialog>
    </>
  )
}

function RolePermissions({
  role,
  canManage,
  onSave,
  saving,
}: {
  role: RoleResponse
  canManage: boolean
  onSave: (permissions: string[]) => void
  saving: boolean
}) {
  const t = useT('roles')
  const tc = useT('common')
  const initial = role.permissions ?? []
  const [value, setValue] = useState(initial.join('\n'))
  const current = value
    .split(/[\n,]/)
    .map((entry) => entry.trim())
    .filter(Boolean)
  const before = new Set(initial)
  const after = new Set(current)
  const changed =
    [...after].filter((code) => !before.has(code)).length + [...before].filter((code) => !after.has(code)).length

  useHotkey(
    'mod+s',
    () => {
      if (changed > 0 && !saving) onSave(current)
    },
    true,
  )

  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-col gap-0.5">
        <h2 className="text-heading text-foreground">{t('permissionsTitle')}</h2>
        <p className="text-small text-muted-foreground">{t('permissionsDescription')}</p>
      </div>
      <PermissionGate
        allowed={canManage}
        fallback={
          <ul className="flex flex-col gap-1 rounded-lg border border-border p-3">
            {initial.map((code) => (
              <li key={code} className="font-mono text-code text-foreground">
                {code}
              </li>
            ))}
          </ul>
        }
      >
        <Field
          label={tc('fields.permissions')}
          hideLabel
          type="textarea"
          mono
          value={value}
          onChange={(event) => setValue(event.target.value)}
          hint={t('permissionsDescription')}
        />
      </PermissionGate>
      {canManage && (
        <UnsavedBar
          count={changed}
          saving={saving}
          onDiscard={() => setValue(initial.join('\n'))}
          onSave={() => onSave(current)}
        />
      )}
    </section>
  )
}

function AssignUser({ onAssign, busy }: { onAssign: (userId: string, assign: boolean) => void; busy: boolean }) {
  const t = useT('roles')
  const [userId, setUserId] = useState('')
  const valid = userId.trim().length > 0
  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-col gap-0.5">
        <h2 className="text-heading text-foreground">{t('assignTitle')}</h2>
        <p className="text-small text-muted-foreground">{t('assignHint')}</p>
      </div>
      <Field
        label={t('userId')}
        value={userId}
        onChange={(event) => setUserId(event.target.value)}
        mono
        placeholder="00000000-0000-0000-0000-000000000000"
      />
      <div className="flex gap-2">
        <Button
          size="sm"
          icon="plus"
          disabled={!valid || busy}
          onClick={() => {
            onAssign(userId.trim(), true)
            setUserId('')
          }}
        >
          {t('assignUser')}
        </Button>
        <Button
          size="sm"
          variant="outline"
          icon="x"
          disabled={!valid || busy}
          onClick={() => {
            onAssign(userId.trim(), false)
            setUserId('')
          }}
        >
          {t('unassignUser')}
        </Button>
      </div>
    </section>
  )
}
