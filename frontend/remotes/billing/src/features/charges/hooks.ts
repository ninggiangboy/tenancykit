import { permissions } from '@suite/api-client'
import { charge as createCharge } from '@suite/api-client/billing'
import { chargesOptions } from '@suite/api-client/billing/query'
import { asListQuery, idempotencyKey, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

export function useChargeList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...chargesOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.charges),
  })
}

export function useCanCreateCharges(): boolean {
  return usePermission(permissions.billing.charge)
}

export function useCreateCharge(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('charges')
  return useAction(
    (body: { walletId: string; amount: number; currency: string; reference?: string; usageRef?: string }) =>
      createCharge({ client: billing, throwOnError: true, headers: idempotencyKey(), body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}
