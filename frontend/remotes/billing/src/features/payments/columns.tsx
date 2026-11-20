import type { PaymentResponse } from '@suite/api-client/billing'
import { Timestamp } from '@suite/remote-kit'
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
      mobile: 'primary',
      cardText: (row) => <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />,
      render: (row) => (
        <span className="font-semibold tabular-nums">
          <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />
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
      key: 'customer_id',
      header: tc('fields.customer'),
      sortable: false,
      mobile: 'secondary',
      cardText: (row) => {
        const customer = row.customerId ? names.get(row.customerId) : undefined
        return customer?.displayName ?? customer?.email ?? row.customerId ?? ''
      },
      render: (row) => <CustomerCell id={row.customerId} names={names} />,
    },
    {
      key: 'created_at',
      header: tc('fields.created'),
      mobile: 'meta',
      cardText: (row) => row.createdAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.createdAt} />,
    },
  ]
}
