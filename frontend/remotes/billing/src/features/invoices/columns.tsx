import type { InvoiceResponse } from '@suite/api-client/billing'
import { Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, Money, StatusBadge } from '@suite/ui'
import { CustomerCell, type CustomerName } from '../../components/lookups'
import { useT } from '../../i18n'

export function useInvoiceColumns(names: Map<string, CustomerName>): DataTableColumn<InvoiceResponse>[] {
  const t = useT('invoices')
  const tc = useT('common')
  return [
    {
      key: 'total',
      header: tc('fields.amount'),
      mobile: 'primary',
      cardText: (row) => <Money minor={row.total?.amount ?? 0} currency={row.total?.currency} />,
      render: (row) => (
        <span className="font-semibold tabular-nums">
          <Money minor={row.total?.amount ?? 0} currency={row.total?.currency} />
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
      key: 'number',
      header: tc('fields.number'),
      sortable: false,
      mobile: 'secondary',
      cardText: (row) => row.number,
      render: (row) => <span className="font-mono text-caption text-muted-foreground">{row.number}</span>,
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
      header: t('issued'),
      mobile: 'meta',
      cardText: (row) => row.issuedAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.issuedAt} />,
    },
  ]
}
