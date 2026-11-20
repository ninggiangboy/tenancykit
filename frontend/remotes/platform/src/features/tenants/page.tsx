import type { TenantResponse } from '@suite/api-client/platform'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../i18n'
import { useTenantColumns } from './columns'
import { CreateTenantDialog } from './components/create-tenant-dialog'
import { TenantSheet } from './components/tenant-sheet'
import { useTenantList, useTenantPermissions } from './hooks/use-tenants'
import { useTenantListSpec } from './list-spec'

const keyOf = (row: TenantResponse) => row.id ?? row.code ?? ''

export function TenantsPage({ state }: { state: UrlState }) {
  const t = useT('tenants')
  const spec = useTenantListSpec()
  const list = useListState(state, spec)
  const query = useTenantList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const can = useTenantPermissions()
  const columns = useTenantColumns()
  const [creating, setCreating] = useState(false)

  const create = (
    <PermissionGate allowed={can.create}>
      <Button icon="plus" onClick={() => setCreating(true)}>
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
      error={query.error}
      refetch={() => void query.refetch()}
      columns={columns}
      rowKey={keyOf}
      onRowOpen={(row) => record.open(keyOf(row))}
      emptyTitle={t('emptyTitle')}
      emptyHint={t('emptyHint')}
      emptyAction={create}
    >
      {record.selected && (
        <TenantSheet tenant={record.selected} onClose={record.close} onPrev={record.prev} onNext={record.next} />
      )}
      <CreateTenantDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
