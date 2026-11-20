import type { RoleResponse } from '@suite/api-client/platform'
import { IdValue, Maybe } from '@suite/remote-kit'
import { type DataTableColumn, StatusBadge } from '@suite/ui'
import { useShowsTenants } from '../../components/tenant-lookup'
import { useT } from '../../i18n'

export function useRoleColumns(tenantCodes: Map<string, string>): DataTableColumn<RoleResponse>[] {
  const t = useT('roles')
  const tc = useT('common')
  const showTenant = useShowsTenants()
  const columns: Array<DataTableColumn<RoleResponse> | false> = [
    {
      key: 'name',
      header: t('name'),
      render: (row) => (
        <span className="flex items-center gap-2">
          <span className="text-body-strong text-foreground">{row.name}</span>
          {row.system && <StatusBadge tone="neutral">{t('system')}</StatusBadge>}
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
            value={row.scope === 'TENANT' && row.tenantId ? (tenantCodes.get(row.tenantId) ?? row.tenantId) : null}
          />
        </span>
      ),
    },
    {
      key: 'permissions',
      header: tc('fields.permissions'),
      sortable: false,
      render: (row) => tc('permissions.count', { count: row.permissions?.length ?? 0 }),
    },
    { key: 'id', header: tc('fields.id'), sortable: false, render: (row) => <IdValue value={row.id} /> },
  ]
  return columns.filter((column): column is DataTableColumn<RoleResponse> => Boolean(column))
}
