import type { UserResponse } from '@suite/api-client/platform'
import { IdValue, Maybe } from '@suite/remote-kit'
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
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.displayName}</span>
          <span className="text-caption text-muted-foreground">{row.email}</span>
        </span>
      ),
    },
    {
      key: 'scope',
      header: tc('fields.scope'),
      render: (row) => <StatusBadge tone="neutral">{tc(`scope.${row.scope}`)}</StatusBadge>,
    },
    showTenant && {
      key: 'tenant',
      header: tc('fields.tenantCode'),
      sortable: false,
      render: (row) => (
        <span className="font-mono text-caption">
          <Maybe
            value={row.tenantId && row.scope === 'TENANT' ? (tenantCodes.get(row.tenantId) ?? row.tenantId) : null}
          />
        </span>
      ),
    },
    { key: 'status', header: tc('fields.status'), render: (row) => <StatusBadge status={row.status} /> },
    { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
  ]
  return columns.filter((column): column is DataTableColumn<UserResponse> => Boolean(column))
}
