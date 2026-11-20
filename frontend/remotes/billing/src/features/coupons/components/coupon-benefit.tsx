import type { CouponResponse } from '@suite/api-client/billing'
import { Money } from '@suite/ui'
import { useT } from '../../../i18n'

/** "10% off", "5.00 USD off" or "5.00 USD credit". */
export function CouponBenefit({ coupon }: { coupon: CouponResponse }) {
  const t = useT('coupons')
  const label = coupon.benefitType ? t(`benefit.${coupon.benefitType}`) : ''
  if (coupon.percentBasisPoints !== undefined && coupon.percentBasisPoints !== null) {
    return (
      <span>
        {label} · {coupon.percentBasisPoints / 100}%
      </span>
    )
  }
  const minor = coupon.amountOff ?? coupon.creditAmount
  return (
    <span>
      {label}
      {minor !== undefined && minor !== null && (
        <>
          {' · '}
          <Money minor={minor} currency={coupon.currency} />
        </>
      )}
    </span>
  )
}
