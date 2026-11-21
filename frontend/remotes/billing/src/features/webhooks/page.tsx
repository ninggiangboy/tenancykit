import type { EndpointResponse } from '@suite/api-client/billing'
import {
  ConfirmDialog,
  DetailRow,
  DetailRows,
  RecordSheet,
  ResourcePage,
  SecretDialog,
  Timestamp,
  useListState,
  useRecordSelection,
} from '@suite/remote-kit'
import { Button, CopyId, type DataTableColumn, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet } from '../../components/facets'
import { useT } from '../../i18n'
import { CreateEndpointDialog } from './components/create-endpoint-dialog'
import { Deliveries } from './components/deliveries'
import { useCanManageWebhooks, useEndpointList, useRotateSecret, useSetEndpointStatus } from './hooks'

const keyOf = (row: EndpointResponse) => row.id ?? ''

function EventTypes({ types }: { types?: string[] }) {
  return (
    <span className="flex flex-wrap gap-1">
      {(types ?? []).map((type) => (
        <span key={type} className="rounded border border-border bg-muted px-1 font-mono text-caption">
          {type}
        </span>
      ))}
    </span>
  )
}

export function WebhooksPage({ state }: { state: UrlState }) {
  const t = useT('webhooks')
  const tc = useT('common')
  const spec = { sortable: ['created_at', 'id'], facets: [useEnumFacet('status', 'status', ['ACTIVE', 'DISABLED'])] }
  const list = useListState(state, spec)
  const query = useEndpointList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const canManage = useCanManageWebhooks()
  const [dialog, setDialog] = useState<'create' | 'rotate' | null>(null)
  const [secret, setSecret] = useState<string | null>(null)
  const showSecret = (value: string | null) => {
    setDialog(null)
    setSecret(value)
  }
  const rotate = useRotateSecret(showSecret)
  const setStatus = useSetEndpointStatus()
  const selected = record.shown

  const columns: DataTableColumn<EndpointResponse>[] = [
    {
      key: 'url',
      header: t('url'),
      sortable: false,
      mobile: 'primary',
      cardText: (row) => row.url,
      render: (row) => (
        <span className="flex min-w-0 flex-col">
          <span className="truncate font-mono text-code text-foreground">{row.url}</span>
          <span className="truncate text-caption text-muted-foreground">{row.description}</span>
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'event_types',
      header: t('eventTypes'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => <EventTypes types={row.eventTypes} />,
    },
    {
      key: 'created_at',
      header: tc('fields.created'),
      mobile: 'meta',
      cardText: (row) => row.createdAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.createdAt} />,
    },
  ]
  const create = (
    <PermissionGate allowed={canManage}>
      <Button icon="plus" onClick={() => setDialog('create')}>
        {t('create')}
      </Button>
    </PermissionGate>
  )

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      primaryAction={create}
      spec={spec}
      list={list}
      rows={rows}
      page={query.data?.page}
      loading={query.isLoading}
      refreshing={query.isFetching && !query.isLoading}
      error={query.error}
      refetch={() => void query.refetch()}
      columns={columns}
      stickyHeader={false}
      rowKey={keyOf}
      onRowOpen={(row) => record.open(keyOf(row))}
      emptyTitle={t('emptyTitle')}
      emptyHint={t('emptyHint')}
      emptyAction={create}
    >
      {selected && (
        <RecordSheet
          open={record.isOpen}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
          eyebrow={t('title')}
          title={<span className="break-all font-mono text-body-strong">{selected.url}</span>}
          description={selected.description}
          actions={
            <PermissionGate allowed={canManage}>
              <Button variant="outline" icon="key" onClick={() => setDialog('rotate')}>
                {t('rotate')}
              </Button>
              <Button
                variant="outline"
                icon={selected.status === 'ACTIVE' ? 'ban' : 'check-circle'}
                loading={setStatus.isPending}
                onClick={() =>
                  setStatus.mutate({
                    id: selected.id ?? '',
                    status: selected.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
                  })
                }
              >
                {selected.status === 'ACTIVE' ? tc('actions.disable') : tc('actions.enable')}
              </Button>
            </PermissionGate>
          }
        >
          <div className="flex flex-col gap-5">
            <DetailRows>
              <DetailRow label={tc('fields.status')}>
                <StatusBadge status={selected.status} />
              </DetailRow>
              <DetailRow label={t('eventTypes')}>
                <span className="flex justify-end">
                  <EventTypes types={selected.eventTypes} />
                </span>
              </DetailRow>
              <DetailRow label={tc('fields.created')}>
                <Timestamp value={selected.createdAt} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <CopyId value={selected.id ?? ''} />
              </DetailRow>
            </DetailRows>
            <Deliveries endpoint={selected} canManage={canManage} />
          </div>
        </RecordSheet>
      )}
      <CreateEndpointDialog open={dialog === 'create'} onClose={() => setDialog(null)} onSecret={showSecret} />
      <ConfirmDialog
        open={dialog === 'rotate'}
        onClose={() => setDialog(null)}
        title={tc('confirm.rotateSecret')}
        description={tc('confirm.rotateSecretHint')}
        confirmLabel={t('rotate')}
        action={rotate}
        onConfirm={() => rotate.mutate(selected?.id ?? '')}
        impact={[{ label: t('url'), value: <span className="break-all font-mono">{selected?.url}</span> }]}
      />
      <SecretDialog open={Boolean(secret)} onClose={() => setSecret(null)} secret={secret} />
    </ResourcePage>
  )
}
