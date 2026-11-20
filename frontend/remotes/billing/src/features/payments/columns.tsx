import type { PaymentResponse } from '@suite/api-client/billing'
import { IdValue, Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, Money, StatusBadge } from '@suite/ui'
import { CustomerCell, type CustomerName } from '../../components/lookups'
import { useT } from '../../i18n'

export function usePaymentColumns(names: Map<string, CustomerName>): DataTableColumn<PaymentResponse>[] {
  const tc = useT('common')
  return [
    {
      key: 'amount',
      header: tc('fields.amount'),
      align: 'end',
      render: (row) => <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />,
    },
    { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'customer_id',
      header: tc('fields.customer'),
      sortable: false,
      render: (row) => <CustomerCell id={row.customerId} names={names} />,
    },
    { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
    { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
  ]
}
