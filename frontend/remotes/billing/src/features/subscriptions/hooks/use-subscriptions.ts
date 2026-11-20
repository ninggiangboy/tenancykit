import { permissions } from '@suite/api-client'
import { cancel, subscribe } from '@suite/api-client/billing'
import { subscription1Options, subscriptionsOptions } from '@suite/api-client/billing/query'
import { asListQuery, idempotencyKey, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useSubscriptionList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...subscriptionsOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.subscriptions),
  })
}

export function useSubscription(id: string | undefined) {
  const { billing } = useApi()
  return useQuery({
    ...subscription1Options({ client: billing, throwOnError: true, path: { id: id ?? '' } }),
    enabled: Boolean(id),
  })
}

export function useCanManageSubscriptions(): boolean {
  return usePermission(permissions.billing.subscribe)
}

export function useSubscribe(onDone: () => void) {
  const { billing } = useApi()
  const tc = useT('common')
  return useAction(
    (body: { walletId: string; planId: string }) =>
      subscribe({ client: billing, throwOnError: true, headers: idempotencyKey(), body }),
    { onSuccess: onDone, successToast: tc('toast.created') },
  )
}

export function useCancelSubscription(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('subscriptions')
  return useAction((id: string) => cancel({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('canceled'),
  })
}
