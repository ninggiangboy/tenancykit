import { permissions } from '@suite/api-client'
import type { CredentialResponse, SystemResponse } from '@suite/api-client/platform'
import { create2, createCredential, revoke, rotate, update } from '@suite/api-client/platform'
import { list2Options, listCredentialsOptions } from '@suite/api-client/platform/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Button, DataTable, Field, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
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
  sortable: ['name', 'created_at', 'id'],
  searchField: 'name',
  searchOp: 'contains',
  searchPlaceholder: 'Search by system name',
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

export function SystemsPage({ state }: { state: UrlState }) {
  const t = useT('systems')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const canRead = usePermission(permissions.platform.list_2)
  const canManage = usePermission(permissions.platform.create_2)
  const canRename = usePermission(permissions.platform.update)
  const canCredentials = usePermission(permissions.platform.listCredentials)
  const canCreate = usePermission(permissions.platform.create_2)
  const list = useListState(state, spec)

  const query = useQuery({
    ...list2Options({ client: platform, throwOnError: true, query: asListQuery<Record<string, string>>(list.params) }),
    enabled: canRead,
  })
  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.id === openId)
  const selectedIndex = rows.findIndex((row) => row.id === openId)

  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [purpose, setPurpose] = useState('')
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameValue, setRenameValue] = useState('')
  const [disableOpen, setDisableOpen] = useState(false)

  const createSystem = useAction(
    (vars: { name: string; purpose: string }) =>
      create2({ client: platform, throwOnError: true, body: { name: vars.name.trim(), purpose: vars.purpose.trim() } }),
    {
      onSuccess: () => {
        setCreateOpen(false)
        setName('')
        setPurpose('')
        toast({ title: t('created'), tone: 'success' })
      },
    },
  )

  const updateSystem = useAction(
    (vars: { id: string; action: 'RENAME' | 'DISABLE'; name?: string }) =>
      update({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { action: vars.action, name: vars.name },
      }),
    {
      onSuccess: (_result, vars) => {
        if (vars.action === 'RENAME') {
          setRenameOpen(false)
          toast({ title: t('renamed'), tone: 'success' })
        } else {
          setDisableOpen(false)
          toast({ title: t('disabled'), tone: 'success' })
        }
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
            header: tc('fields.name'),
            render: (row) => (
              <span className="flex flex-col">
                <span className="text-body-strong text-foreground">{row.name}</span>
                <span className="text-caption text-muted-foreground">{row.purpose}</span>
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
          title={selected.name ?? ''}
          description={selected.purpose}
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
              <PermissionGate allowed={canManage}>
                <Button
                  variant="destructive"
                  size="sm"
                  icon="ban"
                  disabled={selected.status === 'DISABLED'}
                  onClick={() => setDisableOpen(true)}
                >
                  {t('disable')}
                </Button>
              </PermissionGate>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.purpose')}>{selected.purpose}</DetailRow>
              <DetailRow label={tc('fields.status')}>
                <StatusBadge status={selected.status} />
              </DetailRow>
              <DetailRow label={tc('fields.created')}>
                <Timestamp value={selected.createdAt} />
              </DetailRow>
              <DetailRow label={tc('fields.version')}>{selected.version}</DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.id} />
              </DetailRow>
            </DetailRows>
            <Credentials system={selected} canManage={canCredentials} />
          </div>
        </RecordSheet>
      )}

      <FormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('createTitle')}
        description={t('createHint')}
        submitLabel={t('create')}
        busy={createSystem.isPending}
        onSubmit={() => createSystem.mutate({ name, purpose })}
      >
        <Field label={tc('fields.name')} value={name} onChange={(event) => setName(event.target.value)} required />
        <Field label={t('purpose')} value={purpose} onChange={(event) => setPurpose(event.target.value)} required />
      </FormDialog>

      <FormDialog
        open={renameOpen}
        onClose={() => setRenameOpen(false)}
        title={t('renameTitle')}
        submitLabel={tc('actions.save')}
        busy={updateSystem.isPending}
        onSubmit={() => {
          if (selected?.id) updateSystem.mutate({ id: selected.id, action: 'RENAME', name: renameValue })
        }}
      >
        <Field label={tc('fields.name')} value={renameValue} onChange={(event) => setRenameValue(event.target.value)} />
      </FormDialog>

      <ConfirmDialog
        open={disableOpen}
        onClose={() => setDisableOpen(false)}
        title={tc('confirm.disableSystem')}
        description={tc('confirm.disableSystemHint')}
        confirmLabel={t('disable')}
        busy={updateSystem.isPending}
        onConfirm={() => {
          if (selected?.id) updateSystem.mutate({ id: selected.id, action: 'DISABLE' })
        }}
        impact={[
          { label: tc('fields.name'), value: selected?.name ?? '' },
          { label: tc('fields.status'), value: <StatusBadge status={selected?.status} /> },
        ]}
      />
    </>
  )
}

function Credentials({ system, canManage }: { system: SystemResponse; canManage: boolean }) {
  const t = useT('systems')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const [issueOpen, setIssueOpen] = useState(false)
  const [label, setLabel] = useState('')
  const [scopes, setScopes] = useState('')
  const [rotateTarget, setRotateTarget] = useState<CredentialResponse | null>(null)
  const [overlap, setOverlap] = useState('0')
  const [revokeTarget, setRevokeTarget] = useState<CredentialResponse | null>(null)
  const [secret, setSecret] = useState<string | null>(null)

  const query = useQuery({
    ...listCredentialsOptions({ client: platform, throwOnError: true, path: { id: system.id ?? '' } }),
    enabled: Boolean(system.id) && canManage,
  })
  const rows = query.data?.data ?? []

  const issue = useAction(
    (vars: { label: string; scopes: string[] }) =>
      createCredential({
        client: platform,
        throwOnError: true,
        path: { id: system.id ?? '' },
        body: { label: vars.label.trim(), scopes: vars.scopes },
      }),
    {
      onSuccess: (result) => {
        setIssueOpen(false)
        setLabel('')
        setScopes('')
        setSecret(result.data.key ?? null)
        toast({ title: t('credentialCreated'), tone: 'success' })
      },
    },
  )

  const rotateCredential = useAction(
    (vars: { overlapMinutes: number }) =>
      rotate({
        client: platform,
        throwOnError: true,
        path: { id: system.id ?? '' },
        body: { overlapMinutes: vars.overlapMinutes },
      }),
    {
      onSuccess: (result) => {
        setRotateTarget(null)
        setOverlap('0')
        setSecret(result.data.key ?? null)
        toast({ title: t('rotated'), tone: 'success' })
      },
    },
  )

  const revokeCredential = useAction(
    (vars: { credentialId: string }) =>
      revoke({
        client: platform,
        throwOnError: true,
        path: { systemId: system.id ?? '', credentialId: vars.credentialId },
      }),
    {
      onSuccess: () => {
        setRevokeTarget(null)
        toast({ title: t('revoked'), tone: 'success' })
      },
    },
  )

  return (
    <section className="flex flex-col gap-2">
      <div className="flex items-start justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-heading text-foreground">{t('credentialsTitle')}</h2>
          <p className="text-small text-muted-foreground">{t('credentialsDescription')}</p>
        </div>
        <PermissionGate allowed={canManage}>
          <Button size="sm" icon="plus" onClick={() => setIssueOpen(true)}>
            {t('createCredential')}
          </Button>
        </PermissionGate>
      </div>

      {query.error ? (
        <p className="text-small text-destructive">{String((query.error as Error).message)}</p>
      ) : (
        <DataTable
          columns={[
            {
              key: 'label',
              header: tc('fields.label'),
              render: (row) => (
                <span className="flex flex-col">
                  <span className="text-body-strong text-foreground">{row.label ?? row.keyId}</span>
                  <span className="font-mono text-caption text-muted-foreground">{row.keyId}</span>
                </span>
              ),
            },
            {
              key: 'status',
              header: tc('fields.status'),
              render: (row) => <StatusBadge status={row.status} />,
            },
            {
              key: 'expires_at',
              header: t('expires'),
              render: (row) => <Timestamp value={row.expiresAt} />,
            },
            {
              key: 'actions',
              header: tc('fields.actions'),
              sortable: false,
              align: 'end',
              render: (row) => (
                <span className="flex justify-end gap-1">
                  <PermissionGate allowed={canManage && row.status !== 'REVOKED'}>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setRevokeTarget(row)
                      }}
                    >
                      {t('revoke')}
                    </Button>
                  </PermissionGate>
                  <PermissionGate allowed={canManage && row.status !== 'REVOKED'}>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setRotateTarget(row)
                      }}
                    >
                      {t('rotate')}
                    </Button>
                  </PermissionGate>
                </span>
              ),
            },
          ]}
          rows={rows}
          rowKey={(row) => row.id ?? row.keyId ?? ''}
          loading={query.isLoading}
          empty={<p className="p-4 text-small text-muted-foreground">{t('emptyCredentials')}</p>}
        />
      )}

      <FormDialog
        open={issueOpen}
        onClose={() => setIssueOpen(false)}
        title={t('createCredential')}
        description={t('credentialsDescription')}
        submitLabel={tc('actions.create')}
        busy={issue.isPending}
        onSubmit={() =>
          issue.mutate({
            label,
            scopes: scopes
              .split(/[\n,]/)
              .map((entry) => entry.trim())
              .filter(Boolean),
          })
        }
      >
        <Field label={t('credentialLabel')} value={label} onChange={(event) => setLabel(event.target.value)} required />
        <Field
          label={t('scopes')}
          type="textarea"
          mono
          value={scopes}
          onChange={(event) => setScopes(event.target.value)}
        />
      </FormDialog>

      <FormDialog
        open={Boolean(rotateTarget)}
        onClose={() => setRotateTarget(null)}
        title={t('rotate')}
        description={tc('confirm.rotateCredentialHint')}
        submitLabel={t('rotate')}
        busy={rotateCredential.isPending}
        onSubmit={() => rotateCredential.mutate({ overlapMinutes: Number(overlap) || 0 })}
      >
        <Field
          label={t('overlap')}
          type="number"
          min={0}
          value={overlap}
          onChange={(event) => setOverlap(event.target.value)}
        />
      </FormDialog>

      <ConfirmDialog
        open={Boolean(revokeTarget)}
        onClose={() => setRevokeTarget(null)}
        title={tc('confirm.revokeCredential')}
        description={tc('confirm.revokeCredentialHint')}
        confirmLabel={t('revoke')}
        busy={revokeCredential.isPending}
        onConfirm={() => {
          if (revokeTarget?.id) revokeCredential.mutate({ credentialId: revokeTarget.id })
        }}
        impact={[
          { label: tc('fields.system'), value: system.name ?? '' },
          { label: t('expires'), value: <Timestamp value={revokeTarget?.expiresAt} /> },
        ]}
      />

      <SecretDialog open={Boolean(secret)} onClose={() => setSecret(null)} secret={secret} />
    </section>
  )
}
