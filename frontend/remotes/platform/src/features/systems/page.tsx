import type { SystemResponse } from '@suite/api-client/platform'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../i18n'
import { useSystemColumns } from './columns'
import { CreateSystemDialog } from './components/system-dialogs'
import { SystemSheet } from './components/system-sheet'
import { useSystemList, useSystemPermissions } from './hooks/use-systems'
import { useSystemListSpec } from './list-spec'

const keyOf = (row: SystemResponse) => row.id ?? ''

export function SystemsPage({ state }: { state: UrlState }) {
  const t = useT('systems')
  const spec = useSystemListSpec()
  const list = useListState(state, spec)
  const query = useSystemList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const columns = useSystemColumns()
  const can = useSystemPermissions()
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
        <SystemSheet system={record.selected} onClose={record.close} onPrev={record.prev} onNext={record.next} />
      )}
      <CreateSystemDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
