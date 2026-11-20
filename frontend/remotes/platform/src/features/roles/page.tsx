import type { RoleResponse } from '@suite/api-client/platform'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useTenantCodes } from '../../components/tenant-lookup'
import { useT } from '../../i18n'
import { useRoleColumns } from './columns'
import { CreateRoleDialog } from './components/role-dialogs'
import { RoleSheet } from './components/role-sheet'
import { useCanManageRoles, useRoleList } from './hooks/use-roles'
import { useRoleListSpec } from './list-spec'

const keyOf = (row: RoleResponse) => row.id ?? ''

export function RolesPage({ state }: { state: UrlState }) {
  const t = useT('roles')
  const spec = useRoleListSpec()
  const list = useListState(state, spec)
  const query = useRoleList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const tenantCodes = useTenantCodes(rows.map((row) => row.tenantId))
  const columns = useRoleColumns(tenantCodes)
  const canManage = useCanManageRoles()
  const [creating, setCreating] = useState(false)

  const create = (
    <PermissionGate allowed={canManage}>
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
        <RoleSheet
          role={record.selected}
          tenantCode={tenantCodes.get(record.selected.tenantId ?? '')}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
        />
      )}
      <CreateRoleDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
