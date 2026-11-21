import { permissions } from '@suite/api-client'
import { createTopUp, requestRefund } from '@suite/api-client/billing'
import { payment1Options, payments1Options } from '@suite/api-client/billing/query'
import { asListQuery, idempotencyKey, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function usePaymentList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...payments1Options({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.billing.payments_1),
  })
}

export function usePayment(id: string | undefined) {
  const { billing } = useApi()
  return useQuery({
    ...payment1Options({ client: billing, throwOnError: true, path: { id: id ?? '' } }),
    enabled: Boolean(id),
  })
}

export function usePaymentPermissions() {
  return {
    refund: usePermission(permissions.billing.requestRefund),
    topUp: usePermission(permissions.billing.createTopUp),
  }
}

export function useRefundPayment(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('payments')
  return useAction(
    (vars: { paymentId: string; amount: number; currency: string; reason?: string }) =>
      requestRefund({
        client: billing,
        throwOnError: true,
        headers: idempotencyKey(),
        path: { id: vars.paymentId },
        body: { amount: vars.amount, currency: vars.currency, reason: vars.reason },
      }),
    { onSuccess: onDone, successToast: t('refunded') },
  )
}

export function useCreateTopUp(onCheckout: (url: string | null) => void) {
  const { billing } = useApi()
  const t = useT('payments')
  return useAction(
    (body: { walletId: string; amount: number; currency: string }) =>
      createTopUp({ client: billing, throwOnError: true, headers: idempotencyKey(), body }),
    { onSuccess: (result) => onCheckout(result.data.checkoutUrl ?? null), successToast: t('created') },
  )
}
