import type { CustomerResponse } from '@suite/api-client/billing'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../i18n'
import { useCustomerColumns } from './columns'
import { CreateCustomerDialog } from './components/customer-dialogs'
import { CustomerSheet } from './components/customer-sheet'
import { useCanWriteCustomers, useCustomerList } from './hooks/use-customers'
import { useCustomerListSpec } from './list-spec'

const keyOf = (row: CustomerResponse) => row.id ?? ''

export function CustomersPage({ state }: { state: UrlState }) {
  const t = useT('customers')
  const spec = useCustomerListSpec()
  const list = useListState(state, spec)
  const query = useCustomerList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const columns = useCustomerColumns()
  const canWrite = useCanWriteCustomers()
  const [creating, setCreating] = useState(false)

  const create = (
    <PermissionGate allowed={canWrite}>
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
        <CustomerSheet customer={record.selected} onClose={record.close} onPrev={record.prev} onNext={record.next} />
      )}
      <CreateCustomerDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
