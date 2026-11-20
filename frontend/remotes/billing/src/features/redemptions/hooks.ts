import { listQuery, permissions } from '@suite/api-client'
import { attach, redeemByCode, revoke } from '@suite/api-client/billing'
import { couponsOptions, redemptionsOfWalletOptions } from '@suite/api-client/billing/query'
import { asListQuery, type EntityOptions, idempotencyKey, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

export function useWalletRedemptions(walletId: string) {
  const { billing } = useApi()
  return useQuery({
    ...redemptionsOfWalletOptions({ client: billing, throwOnError: true, path: { walletId } }),
    enabled: usePermission(permissions.billing.redemptionsOfWallet) && walletId !== '',
  })
}

export function useRedemptionPermissions() {
  return { revoke: usePermission(permissions.billing.revoke), redeem: usePermission(permissions.billing.redeemByCode) }
}

/** Active coupons, filtered on this page by name. */
export function useCouponOptions(search: string): EntityOptions {
  const { billing } = useApi()
  const query = useQuery({
    ...couponsOptions({
      client: billing,
      throwOnError: true,
      query: asListQuery(listQuery({ filters: [{ field: 'status', op: 'eq', value: 'ACTIVE' }], limit: 100 })),
    }),
    enabled: usePermission(permissions.billing.coupons),
    placeholderData: keepPreviousData,
  })
  const needle = search.trim().toLowerCase()
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? [])
      .filter((coupon) => !needle || (coupon.name ?? '').toLowerCase().includes(needle))
      .map((coupon) => ({ value: coupon.id ?? '', label: coupon.name ?? '', hint: coupon.id })),
  }
}

export function useRedeemCode(walletId: string, onDone: () => void) {
  const { billing } = useApi()
  const t = useT('redemptions')
  return useAction(
    (code: string) =>
      redeemByCode({
        client: billing,
        throwOnError: true,
        path: { walletId },
        headers: idempotencyKey(),
        body: { code },
      }),
    { onSuccess: onDone, successToast: t('redeemed') },
  )
}

export function useAttachCoupon(walletId: string, onDone: () => void) {
  const { billing } = useApi()
  const t = useT('redemptions')
  return useAction(
    (couponId: string) =>
      attach({
        client: billing,
        throwOnError: true,
        path: { walletId },
        headers: idempotencyKey(),
        body: { couponId },
      }),
    { onSuccess: onDone, successToast: t('attached') },
  )
}

export function useRevokeRedemption(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('redemptions')
  return useAction((id: string) => revoke({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('revoked'),
  })
}
