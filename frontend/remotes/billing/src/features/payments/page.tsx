import type { PaymentResponse } from '@suite/api-client/billing'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, PermissionGate, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useCustomerNames } from '../../components/lookups'
import { useT } from '../../i18n'
import { usePaymentColumns } from './columns'
import { PaymentSheet } from './components/payment-sheet'
import { TopUpDialog } from './components/top-up-dialog'
import { usePaymentList, usePaymentPermissions } from './hooks/use-payments'
import { usePaymentListSpec } from './list-spec'

const keyOf = (row: PaymentResponse) => row.id ?? ''

export function PaymentsPage({ state }: { state: UrlState }) {
  const t = useT('payments')
  const spec = usePaymentListSpec()
  const list = useListState(state, spec)
  const query = usePaymentList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const names = useCustomerNames(rows.map((row) => row.customerId))
  const columns = usePaymentColumns(names)
  const can = usePaymentPermissions()
  const [toppingUp, setToppingUp] = useState(false)

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      actions={
        <PermissionGate allowed={can.topUp}>
          <Button icon="plus" onClick={() => setToppingUp(true)}>
            {t('topUp')}
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
        <PaymentSheet
          payment={record.selected}
          names={names}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
        />
      )}
      <TopUpDialog open={toppingUp} onClose={() => setToppingUp(false)} />
    </ResourcePage>
  )
}
