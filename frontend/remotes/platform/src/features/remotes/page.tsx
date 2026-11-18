import { etag, permissions } from '@suite/api-client'
import type { RemoteResponse, StateRequest } from '@suite/api-client/platform'
import { setState } from '@suite/api-client/platform'
import { listRemotesOptions } from '@suite/api-client/platform/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Alert, AppMark, Button, PermissionGate, StatusBadge, Switch, type UrlState } from '@suite/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useT } from '../../i18n'
import {
  asListQuery,
  DetailRow,
  DetailRows,
  IdValue,
  type ListSpec,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useListState,
} from '../../lib/page'
import { useAction, useApi, useToast } from '../../lib/runtime'

const spec: ListSpec = {
  sortable: ['name'],
  searchField: 'name',
  searchOp: 'contains',
  searchPlaceholder: 'Search by remote name',
  views: [
    { id: 'all', label: 'All' },
    { id: 'enabled', label: 'Enabled', filters: [{ field: 'enabled', op: 'eq', value: true }] },
    { id: 'disabled', label: 'Disabled', filters: [{ field: 'enabled', op: 'eq', value: false }] },
  ],
}

export function RemotesPage({ state }: { state: UrlState }) {
  const t = useT('remotes')
  const tc = useT('common')
  const toast = useToast()
  const { platform } = useApi()
  const queryClient = useQueryClient()
  const canRead = usePermission(permissions.platform.listRemotes)
  const canManage = usePermission(permissions.platform.setState)
  const list = useListState(state, spec)

  const query = useQuery({
    ...listRemotesOptions({
      client: platform,
      throwOnError: true,
      query: asListQuery<Record<string, string>>(list.params),
    }),
    enabled: canRead,
  })
  const rows = query.data?.data ?? []
  const openId = state.get('open')
  const selected = rows.find((row) => row.name === openId)
  const selectedIndex = rows.findIndex((row) => row.name === openId)
  const [optimistic, setOptimistic] = useState<Record<string, boolean>>({})

  const applyState = async (name: string, body: StateRequest, version: number) => {
    const result = await setState({
      client: platform,
      throwOnError: true,
      path: { name },
      body,
      headers: { 'If-Match': etag(version) },
    })
    await queryClient.invalidateQueries()
    return result
  }

  const changeState = useAction(
    (vars: { name: string; body: StateRequest; version: number; previousEnabled: boolean; previousVersion?: string }) =>
      applyState(vars.name, vars.body, vars.version),
    {
      onSuccess: (result, vars) => {
        setOptimistic((current) => {
          const next = { ...current }
          delete next[vars.name]
          return next
        })
        if (vars.body.activeVersion) {
          toast({
            title: t('activated', { name: vars.name, version: vars.body.activeVersion }),
            tone: 'success',
            action:
              vars.previousVersion !== undefined
                ? {
                    label: tc('actions.undo'),
                    onClick: () =>
                      void applyState(
                        vars.name,
                        { enabled: result.data.enabled ?? false, activeVersion: vars.previousVersion },
                        result.data.version ?? vars.version,
                      ),
                  }
                : undefined,
          })
          return
        }
        const enabled = vars.body.enabled ?? false
        toast({
          title: enabled ? t('enabled') : t('disabled'),
          tone: 'success',
          action: {
            label: tc('actions.undo'),
            onClick: () =>
              void applyState(vars.name, { enabled: vars.previousEnabled }, result.data.version ?? vars.version),
          },
        })
      },
      onError: (_error, vars) => {
        setOptimistic((current) => {
          const next = { ...current }
          delete next[vars.name]
          return next
        })
      },
    },
  )

  const toggle = (remote: RemoteResponse, enabled: boolean) => {
    if (!remote.name || remote.version === undefined) return
    setOptimistic((current) => ({ ...current, [remote.name as string]: enabled }))
    changeState.mutate({
      name: remote.name,
      body: { enabled },
      version: remote.version,
      previousEnabled: remote.enabled ?? false,
    })
  }

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
        rowKey={(row) => row.name ?? ''}
        caption={t('title')}
        emptyTitle={t('emptyTitle')}
        emptyHint={t('emptyHint')}
        columns={[
          {
            key: 'name',
            header: tc('fields.name'),
            render: (row) => (
              <span className="flex items-center gap-2">
                <AppMark app={row.appCode ?? row.name ?? ''} size="sm" />
                <span className="flex flex-col">
                  <span className="text-body-strong text-foreground">{row.title ?? row.name}</span>
                  <span className="font-mono text-caption text-muted-foreground">{row.name}</span>
                </span>
              </span>
            ),
          },
          {
            key: 'app_code',
            header: tc('fields.audiences'),
            sortable: false,
            render: (row) => <span className="text-small">{row.audiences?.join(', ')}</span>,
          },
          {
            key: 'activeVersion',
            header: tc('fields.activeVersion'),
            sortable: false,
            render: (row) => <span className="font-mono text-code">{row.activeVersion ?? tc('state.none')}</span>,
          },
          {
            key: 'enabled',
            header: tc('fields.enabled'),
            sortable: false,
            render: (row) => (
              <PermissionGate allowed={canManage}>
                <Switch
                  aria-label={t('enable')}
                  checked={optimistic[row.name ?? ''] ?? row.enabled ?? false}
                  busy={changeState.isPending && changeState.variables?.name === row.name}
                  onCheckedChange={(checked) => toggle(row, checked)}
                />
              </PermissionGate>
            ),
          },
          {
            key: 'versions',
            header: t('versions'),
            sortable: false,
            render: (row) => <span>{t('versionCount', { count: row.versions?.length ?? 0 })}</span>,
          },
        ]}
      />

      {selected && (
        <RecordSheet
          state={state}
          open={Boolean(openId)}
          eyebrow={t('title')}
          title={selected.title ?? selected.name ?? ''}
          description={selected.appCode}
          onPrev={selectedIndex > 0 ? () => state.set({ open: rows[selectedIndex - 1]?.name ?? null }) : undefined}
          onNext={
            selectedIndex < rows.length - 1
              ? () => state.set({ open: rows[selectedIndex + 1]?.name ?? null })
              : undefined
          }
        >
          <div className="flex flex-col gap-4">
            <DetailRows>
              <DetailRow label={tc('fields.appCode')}>{selected.appCode}</DetailRow>
              <DetailRow label={tc('fields.audiences')}>{selected.audiences?.join(', ')}</DetailRow>
              <DetailRow label={tc('fields.enabled')}>
                <StatusBadge tone={selected.enabled ? 'success' : 'neutral'}>
                  {selected.enabled ? tc('state.on') : tc('state.off')}
                </StatusBadge>
              </DetailRow>
              <DetailRow label={tc('fields.activeVersion')}>
                <span className="font-mono text-code">{selected.activeVersion ?? tc('state.none')}</span>
              </DetailRow>
              <DetailRow label={tc('fields.created')}>
                <Timestamp value={selected.createdAt} />
              </DetailRow>
              <DetailRow label={tc('fields.version')}>{selected.version}</DetailRow>
              <DetailRow label={tc('fields.id')}>
                <IdValue value={selected.name} />
              </DetailRow>
            </DetailRows>

            <section className="flex flex-col gap-2">
              <div className="flex flex-col gap-0.5">
                <h2 className="text-heading text-foreground">{t('versions')}</h2>
                <p className="text-small text-muted-foreground">{t('activateHint')}</p>
              </div>
              {selected.versions && selected.versions.length > 0 ? (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {selected.versions.map((version) => {
                    const active = version.version === selected.activeVersion
                    return (
                      <li key={version.version} className="flex items-center justify-between gap-3 px-3 py-2.5">
                        <span className="flex flex-col">
                          <span className="flex items-center gap-2">
                            <span className="font-mono text-code text-foreground">{version.version}</span>
                            {active && <StatusBadge status="ACTIVE">{t('active')}</StatusBadge>}
                          </span>
                          <span className="text-caption text-muted-foreground">{version.entryPath}</span>
                          <span className="text-caption text-muted-foreground">
                            {tc('fields.sdkMajor')} {version.sdkMajor} · {tc('fields.registered')}{' '}
                            <Timestamp value={version.registeredAt} />
                          </span>
                        </span>
                        <PermissionGate allowed={canManage}>
                          <Button
                            variant={active ? 'ghost' : 'outline'}
                            size="sm"
                            icon={active ? 'check' : 'refresh'}
                            disabled={active || !selected.name || selected.version === undefined}
                            onClick={() => {
                              if (!selected.name || selected.version === undefined || !version.version) return
                              changeState.mutate({
                                name: selected.name,
                                // Enabling is its own write; activating a version keeps whatever the remote is now.
                                body: { enabled: selected.enabled ?? false, activeVersion: version.version },
                                version: selected.version,
                                previousEnabled: selected.enabled ?? false,
                                previousVersion: selected.activeVersion,
                              })
                            }}
                          >
                            {active ? t('active') : tc('actions.activate')}
                          </Button>
                        </PermissionGate>
                      </li>
                    )
                  })}
                </ul>
              ) : (
                <Alert tone="info" title={t('emptyTitle')}>
                  {t('emptyHint')}
                </Alert>
              )}
            </section>
          </div>
        </RecordSheet>
      )}
    </>
  )
}
