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
      render: (row) => (
        <span className="flex flex-col">
          <Money minor={row.total?.amount ?? 0} currency={row.total?.currency} />
          <span className="font-mono text-caption text-muted-foreground">{row.number}</span>
        </span>
      ),
    },
    { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'customer_id',
      header: tc('fields.customer'),
      sortable: false,
      render: (row) => <CustomerCell id={row.customerId} names={names} />,
    },
    { key: 'created_at', header: t('issued'), render: (row) => <Timestamp value={row.issuedAt} /> },
  ]
}
