import type { SystemResponse } from '@suite/api-client/platform'
import { Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useT } from '../../i18n'

export function useSystemColumns(): DataTableColumn<SystemResponse>[] {
  const tc = useT('common')
  return [
    {
      key: 'name',
      header: tc('fields.name'),
      mobile: 'primary',
      cardText: (row) => row.name,
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.name}</span>
          <span className="text-caption text-muted-foreground">{row.purpose}</span>
        </span>
      ),
    },
    {
      key: 'purpose',
      header: tc('fields.purpose'),
      mobile: 'secondary',
      cardOnly: true,
      cardText: (row) => row.purpose,
      render: (row) => <span className="text-caption text-muted-foreground">{row.purpose}</span>,
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
