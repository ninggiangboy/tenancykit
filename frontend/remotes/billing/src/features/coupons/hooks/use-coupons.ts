import { permissions } from '@suite/api-client'
import type { CreateCouponRequest } from '@suite/api-client/billing'
import { addCode, createCoupon, deactivateCode, deactivateCoupon } from '@suite/api-client/billing'
import { codesOptions, couponsOptions } from '@suite/api-client/billing/query'
import { asListQuery, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useCouponList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...couponsOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.billing.coupons),
  })
}

export function useCouponCodes(couponId: string | undefined) {
  const { billing } = useApi()
  return useQuery({
    ...codesOptions({ client: billing, throwOnError: true, path: { id: couponId ?? '' } }),
    enabled: Boolean(couponId),
  })
}

export function useCanManageCoupons(): boolean {
  return usePermission(permissions.billing.createCoupon)
}

export function useCreateCoupon(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('coupons')
  return useAction((body: CreateCouponRequest) => createCoupon({ client: billing, throwOnError: true, body }), {
    onSuccess: onDone,
    successToast: t('created'),
  })
}

export function useDeactivateCoupon(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('coupons')
  return useAction(
    (vars: { id: string; revokeRedemptions: boolean }) =>
      deactivateCoupon({
        client: billing,
        throwOnError: true,
        path: { id: vars.id },
        body: { revokeRedemptions: vars.revokeRedemptions },
      }),
    { onSuccess: onDone, successToast: t('deactivated') },
  )
}

export function useAddCode(couponId: string, onDone: () => void) {
  const { billing } = useApi()
  const t = useT('coupons')
  return useAction(
    (body: { code: string; maxRedemptions?: number }) =>
      addCode({ client: billing, throwOnError: true, path: { id: couponId }, body }),
    { onSuccess: onDone, successToast: t('codeAdded') },
  )
}

export function useDeactivateCode() {
  const { billing } = useApi()
  const t = useT('coupons')
  return useAction((id: string) => deactivateCode({ client: billing, throwOnError: true, path: { id } }), {
    successToast: t('codeDeactivated'),
  })
}
