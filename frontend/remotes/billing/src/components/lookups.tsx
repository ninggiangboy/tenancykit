import { listQuery, permissions } from '@suite/api-client'
import { list1Options, listPlansOptions, wallet1Options, walletsOptions } from '@suite/api-client/billing/query'
import { asListQuery, type EntityOptions, IdValue, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'

export interface CustomerName {
  displayName?: string
  email?: string
}

function unique(ids: Array<string | null | undefined>): string[] {
  return [...new Set(ids.filter((id): id is string => Boolean(id)))].sort()
}

/** The name and email of every customer id on screen, in one request. */
export function useCustomerNames(ids: Array<string | null | undefined>): Map<string, CustomerName> {
  const { billing } = useApi()
  const wanted = unique(ids)
  const query = useQuery({
    ...list1Options({
      client: billing,
      throwOnError: true,
      query: asListQuery(
        listQuery({ filters: [{ field: 'id', op: 'in', value: wanted.join(',') }], limit: Math.max(wanted.length, 1) }),
      ),
    }),
    enabled: usePermission(permissions.billing.list_1) && wanted.length > 0,
    staleTime: 60_000,
  })
  return new Map((query.data?.data ?? []).map((customer) => [customer.id ?? '', customer]))
}

/** A customer as a person reads one: the name, with the email under it; the id when the name is not known. */
export function CustomerCell({ id, names }: { id?: string | null; names: Map<string, CustomerName> }) {
  const customer = id ? names.get(id) : undefined
  if (!customer) return <IdValue value={id} />
  return (
    <span className="flex min-w-0 flex-col">
      <span className="truncate text-foreground">{customer.displayName ?? customer.email}</span>
      {customer.email && customer.displayName && (
        <span className="truncate text-caption text-muted-foreground">{customer.email}</span>
      )}
    </span>
  )
}

export function useCustomerOptions(search: string): EntityOptions {
  const { billing } = useApi()
  const query = useQuery({
    ...list1Options({
      client: billing,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: search ? [{ field: 'display_name', op: 'starts_with', value: search }] : [],
          limit: 20,
        }),
      ),
    }),
    enabled: usePermission(permissions.billing.list_1),
    placeholderData: keepPreviousData,
  })
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? []).map((customer) => ({
      value: customer.id ?? '',
      label: customer.displayName ?? customer.email ?? customer.id ?? '',
      hint: customer.email,
    })),
  }
}

/** Wallets, labelled by currency and mode with the owner's name; the search matches either, on this page of wallets. */
export function useWalletOptions(search: string, customerId?: string): EntityOptions {
  const { billing } = useApi()
  const query = useQuery({
    ...walletsOptions({
      client: billing,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: customerId ? [{ field: 'customer_id', op: 'eq', value: customerId }] : [],
          sort: '-created_at',
          limit: 100,
        }),
      ),
    }),
    enabled: usePermission(permissions.billing.wallets),
    placeholderData: keepPreviousData,
  })
  const wallets = query.data?.data ?? []
  const names = useCustomerNames(wallets.map((wallet) => wallet.customerId))
  const needle = search.trim().toLowerCase()
  return {
    loading: query.isFetching,
    options: wallets
      .map((wallet) => {
        const owner = names.get(wallet.customerId ?? '')
        return {
          value: wallet.id ?? '',
          label:
            `${owner?.displayName ?? owner?.email ?? wallet.customerId ?? ''} · ${wallet.currency ?? ''} ${wallet.mode ?? ''}`.trim(),
          hint: wallet.id,
        }
      })
      .filter((option) => !needle || option.label.toLowerCase().includes(needle) || option.value.startsWith(needle)),
  }
}

export function usePlanOptions(search: string): EntityOptions {
  const { billing } = useApi()
  const query = useQuery({
    ...listPlansOptions({
      client: billing,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: search ? [{ field: 'code', op: 'starts_with', value: search }] : [],
          limit: 20,
        }),
      ),
    }),
    enabled: usePermission(permissions.billing.listPlans),
    placeholderData: keepPreviousData,
  })
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? [])
      .filter((plan) => plan.status !== 'RETIRED')
      .map((plan) => ({ value: plan.id ?? '', label: plan.name ?? plan.code ?? '', hint: plan.code })),
  }
}

/** One wallet by id, for a form that follows the chosen wallet's currency. */
export function useWallet(walletId: string | undefined) {
  const { billing } = useApi()
  const valid = Boolean(walletId && /^[0-9a-f-]{36}$/i.test(walletId))
  return useQuery({
    ...wallet1Options({ client: billing, throwOnError: true, path: { id: walletId ?? '' } }),
    enabled: valid,
    staleTime: 60_000,
  })
}
