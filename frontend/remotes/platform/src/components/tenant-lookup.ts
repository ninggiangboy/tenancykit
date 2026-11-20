import { listQuery, permissions } from '@suite/api-client'
import { list1Options, listOptions } from '@suite/api-client/platform/query'
import { asListQuery, type EntityOptions, useApi } from '@suite/remote-kit'
import { usePermission, useShell } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'

/**
 * The code of each tenant id on screen, in one request. Only an operator who may read tenants asks; a tenant's own
 * people see one tenant and get an empty map.
 */
export function useTenantCodes(ids: Array<string | null | undefined>): Map<string, string> {
  const { platform } = useApi()
  const ctx = useShell()
  const canRead = usePermission(permissions.platform.list_1)
  const unique = [...new Set(ids.filter((id): id is string => Boolean(id)))].sort()
  const query = useQuery({
    ...list1Options({
      client: platform,
      throwOnError: true,
      query: asListQuery(
        listQuery({ filters: [{ field: 'id', op: 'in', value: unique.join(',') }], limit: Math.max(unique.length, 1) }),
      ),
    }),
    enabled: canRead && ctx.user.scope === 'PLATFORM' && unique.length > 0,
    staleTime: 60_000,
  })
  return new Map((query.data?.data ?? []).map((tenant) => [tenant.id ?? '', tenant.code ?? '']))
}

/** Whether a tenant column means anything to this caller. */
export function useShowsTenants(): boolean {
  return useShell().user.scope === 'PLATFORM'
}

/** Tenant options by code prefix; the value is the code or the id, as the form needs. */
export function useTenantOptions(search: string, valueBy: 'code' | 'id' = 'id'): EntityOptions {
  const { platform } = useApi()
  const query = useQuery({
    ...list1Options({
      client: platform,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: search ? [{ field: 'code', op: 'starts_with', value: search.toLowerCase() }] : [],
          sort: 'name',
          limit: 20,
        }),
      ),
    }),
    enabled: usePermission(permissions.platform.list_1),
    placeholderData: keepPreviousData,
  })
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? []).map((tenant) => ({
      value: (valueBy === 'code' ? tenant.code : tenant.id) ?? '',
      label: tenant.name ?? tenant.code ?? '',
      hint: tenant.code,
    })),
  }
}

/** User options by display name prefix; the value is the user id. */
export function useUserOptions(search: string): EntityOptions {
  const { platform } = useApi()
  const query = useQuery({
    ...listOptions({
      client: platform,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: search ? [{ field: 'email', op: 'contains', value: search }] : [],
          limit: 20,
        }),
      ),
    }),
    enabled: usePermission(permissions.platform.list),
    placeholderData: keepPreviousData,
  })
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? []).map((user) => ({
      value: user.id ?? '',
      label: user.displayName ?? user.email ?? '',
      hint: user.email,
    })),
  }
}
