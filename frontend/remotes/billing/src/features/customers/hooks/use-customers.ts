import { listQuery, permissions } from '@suite/api-client'
import { create, deactivate, update } from '@suite/api-client/billing'
import { list1Options } from '@suite/api-client/billing/query'
import { list2Options } from '@suite/api-client/platform/query'
import { asListQuery, type EntityOptions, idempotencyKey, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useCustomerList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...list1Options({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.list_1),
  })
}

export function useCanWriteCustomers(): boolean {
  return usePermission(permissions.billing.create)
}

/** The tenant's own systems, from Platform, for the identity a customer is created with. */
export function useTenantSystemOptions(search: string): EntityOptions {
  const { platform } = useApi()
  const query = useQuery({
    ...list2Options({
      client: platform,
      throwOnError: true,
      query: asListQuery(
        listQuery({ filters: search ? [{ field: 'name', op: 'contains', value: search }] : [], limit: 20 }),
      ),
    }),
    enabled: usePermission(permissions.platform.list_2),
    placeholderData: keepPreviousData,
  })
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? []).map((system) => ({
      value: system.id ?? '',
      label: system.name ?? '',
      hint: system.purpose,
    })),
  }
}

export function useCreateCustomer(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('customers')
  return useAction(
    (body: { displayName: string; email?: string; tenantSystemId?: string; externalUserId?: string }) =>
      create({ client: billing, throwOnError: true, headers: idempotencyKey(), body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

export function useUpdateCustomer(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('customers')
  return useAction(
    (vars: { id: string; displayName: string; email?: string }) =>
      update({
        client: billing,
        throwOnError: true,
        path: { id: vars.id },
        body: { displayName: vars.displayName, email: vars.email },
      }),
    { onSuccess: onDone, successToast: t('updated') },
  )
}

export function useDeactivateCustomer(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('customers')
  return useAction((id: string) => deactivate({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('deactivated'),
  })
}
