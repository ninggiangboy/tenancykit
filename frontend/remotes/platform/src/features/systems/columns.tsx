import type { SystemResponse } from '@suite/api-client/platform'
import { IdValue, Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useT } from '../../i18n'

export function useSystemColumns(): DataTableColumn<SystemResponse>[] {
  const tc = useT('common')
  return [
    {
      key: 'name',
      header: tc('fields.name'),
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.name}</span>
          <span className="text-caption text-muted-foreground">{row.purpose}</span>
        </span>
      ),
    },
    { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created_at', header: tc('fields.created'), render: (row) => <Timestamp value={row.createdAt} /> },
    { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
  ]
}
