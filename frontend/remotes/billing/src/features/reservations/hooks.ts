import { permissions } from '@suite/api-client'
import { capture, release, reserve } from '@suite/api-client/billing'
import { reservationsOptions } from '@suite/api-client/billing/query'
import { asListQuery, idempotencyKey, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

export function useReservationList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...reservationsOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.reservations),
  })
}

export function useCanManageReservations(): boolean {
  return usePermission(permissions.billing.reserve)
}

export function useReserve(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('reservations')
  return useAction(
    (body: { walletId: string; amount: number; currency: string; ttlSeconds?: number; reference?: string }) =>
      reserve({ client: billing, throwOnError: true, headers: idempotencyKey(), body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

export function useCapture(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('reservations')
  return useAction(
    (vars: { id: string; amount: number; currency: string; reference?: string }) =>
      capture({
        client: billing,
        throwOnError: true,
        headers: idempotencyKey(),
        path: { id: vars.id },
        body: { amount: vars.amount, currency: vars.currency, reference: vars.reference },
      }),
    { onSuccess: onDone, successToast: t('captured') },
  )
}

export function useRelease(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('reservations')
  return useAction((id: string) => release({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('released'),
  })
}
