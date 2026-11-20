import type { UserResponse } from '@suite/api-client/platform'
import { Maybe } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useShowsTenants } from '../../components/tenant-lookup'
import { useT } from '../../i18n'

export function useUserColumns(tenantCodes: Map<string, string>): DataTableColumn<UserResponse>[] {
  const tc = useT('common')
  const showTenant = useShowsTenants()
  const columns: Array<DataTableColumn<UserResponse> | false> = [
    {
      key: 'display_name',
      header: tc('fields.displayName'),
      mobile: 'primary',
      cardText: (row) => row.displayName,
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.displayName}</span>
          <span className="text-caption text-muted-foreground">{row.email}</span>
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
      key: 'scope',
      header: tc('fields.scope'),
      mobile: 'secondary',
      render: (row) => <span className="text-small">{tc(`scope.${row.scope}`)}</span>,
    },
    showTenant && {
      key: 'tenant',
      header: tc('fields.tenantCode'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => (
        <span className="font-mono text-caption">
          <Maybe
            value={row.tenantId && row.scope === 'TENANT' ? (tenantCodes.get(row.tenantId) ?? row.tenantId) : null}
          />
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
    },
  ]
  return columns.filter((column): column is DataTableColumn<UserResponse> => Boolean(column))
}
