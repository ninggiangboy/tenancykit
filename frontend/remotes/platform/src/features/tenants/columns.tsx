import type { TenantResponse } from '@suite/api-client/platform'
import { Timestamp } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useT } from '../../i18n'

export function useTenantColumns(): DataTableColumn<TenantResponse>[] {
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
          <span className="font-mono text-caption text-muted-foreground">{row.code}</span>
        </span>
      ),
    },
    {
      key: 'code',
      header: tc('fields.code'),
      mobile: 'secondary',
      cardOnly: true,
      cardText: (row) => row.code,
      render: (row) => <span className="font-mono text-caption text-muted-foreground">{row.code}</span>,
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
