import type { UserResponse } from '@suite/api-client/platform'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useTenantCodes } from '../../components/tenant-lookup'
import { useT } from '../../i18n'
import { useUserColumns } from './columns'
import { CreateUserDialog } from './components/create-user-dialog'
import { UserSheet } from './components/user-sheet'
import { useUserList, useUserPermissions } from './hooks/use-users'
import { useUserListSpec } from './list-spec'

const keyOf = (row: UserResponse) => row.id ?? ''

export function UsersPage({ state }: { state: UrlState }) {
  const t = useT('users')
  const spec = useUserListSpec()
  const list = useListState(state, spec)
  const query = useUserList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const tenantCodes = useTenantCodes(rows.map((row) => row.tenantId))
  const columns = useUserColumns(tenantCodes)
  const can = useUserPermissions()
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
      actions={create}
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
        <UserSheet
          user={record.selected}
          tenantCode={tenantCodes.get(record.selected.tenantId ?? '')}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
        />
      )}
      <CreateUserDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
