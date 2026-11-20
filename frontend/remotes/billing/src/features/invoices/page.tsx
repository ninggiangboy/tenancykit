import type { InvoiceResponse } from '@suite/api-client/billing'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useCustomerNames } from '../../components/lookups'
import { useT } from '../../i18n'
import { useInvoiceColumns } from './columns'
import { CreateInvoiceDialog } from './components/invoice-dialogs'
import { InvoiceSheet } from './components/invoice-sheet'
import { useInvoiceList, useInvoicePermissions } from './hooks/use-invoices'
import { useInvoiceListSpec } from './list-spec'

const keyOf = (row: InvoiceResponse) => row.id ?? ''

export function InvoicesPage({ state }: { state: UrlState }) {
  const t = useT('invoices')
  const spec = useInvoiceListSpec()
  const list = useListState(state, spec)
  const query = useInvoiceList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const names = useCustomerNames(rows.map((row) => row.customerId))
  const columns = useInvoiceColumns(names)
  const can = useInvoicePermissions()
  const [creating, setCreating] = useState(false)

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      actions={
        <PermissionGate allowed={can.create}>
          <Button icon="plus" onClick={() => setCreating(true)}>
            {t('create')}
          </Button>
        </PermissionGate>
      }
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
    >
      {record.selected && (
        <InvoiceSheet
          invoice={record.selected}
          names={names}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
        />
      )}
      <CreateInvoiceDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
