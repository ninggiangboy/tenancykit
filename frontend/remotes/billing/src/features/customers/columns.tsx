import type { CustomerResponse } from '@suite/api-client/billing'
import { IdValue, Maybe, Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useT } from '../../i18n'

export function useCustomerColumns(): DataTableColumn<CustomerResponse>[] {
  const tc = useT('common')
  return [
    {
      key: 'display_name',
      header: tc('fields.customer'),
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.displayName}</span>
          <span className="text-caption text-muted-foreground">
            <Maybe value={row.email} />
          </span>
        </span>
      ),
    },
    { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
    { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
  ]
}
