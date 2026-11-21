import { FormDialog, FormEntityPicker, FormField, rules, useZodForm } from '@suite/remote-kit'
import { z } from 'zod'
import { useT } from '../../../i18n'
import { useAttachCoupon, useCouponOptions, useRedeemCode } from '../hooks'

interface Props {
  walletId: string
  open: boolean
  onClose: () => void
}

const redeemSchema = z.object({ code: rules.text() })
const attachSchema = z.object({ couponId: rules.id() })

export function RedeemCodeDialog({ walletId, open, onClose }: Props) {
  const t = useT('redemptions')
  const form = useZodForm(redeemSchema, { code: '' })
  const redeem = useRedeemCode(walletId, () => {
    onClose()
    form.reset()
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('redeem')}
      submitLabel={t('redeem')}
      action={redeem}
      form={form}
      onSubmit={(values) => redeem.mutate(values.code)}
    >
      <FormField control={form.control} name="code" label={t('redeemCode')} hint={t('redeemHint')} mono autoFocus />
    </FormDialog>
  )
}

export function AttachCouponDialog({ walletId, open, onClose }: Props) {
  const t = useT('redemptions')
  const form = useZodForm(attachSchema, { couponId: '' })
  const attach = useAttachCoupon(walletId, () => {
    onClose()
    form.reset()
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('attach')}
      description={t('attachHint')}
      submitLabel={t('attach')}
      action={attach}
      form={form}
      onSubmit={(values) => attach.mutate(values.couponId)}
    >
      <FormEntityPicker control={form.control} name="couponId" label={t('coupon')} useOptions={useCouponOptions} />
    </FormDialog>
  )
}
