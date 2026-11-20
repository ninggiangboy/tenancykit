import type { CustomerResponse } from '@suite/api-client/billing'
import { Maybe, Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useT } from '../../i18n'

export function useCustomerColumns(): DataTableColumn<CustomerResponse>[] {
  const tc = useT('common')
  return [
    {
      key: 'display_name',
      header: tc('fields.customer'),
      mobile: 'primary',
      cardText: (row) => row.displayName,
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.displayName}</span>
          <span className="text-caption text-muted-foreground">
            <Maybe value={row.email} />
          </span>
        </span>
      ),
    },
    {
      key: 'email',
      header: tc('fields.email'),
      mobile: 'secondary',
      cardOnly: true,
      cardText: (row) => row.email,
      render: (row) => <span className="text-caption text-muted-foreground">{row.email}</span>,
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
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
