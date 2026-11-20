import { FormDialog, FormField, FormSwitch, useZodForm } from '@suite/remote-kit'
import { asOptions, CURRENCIES } from '../../../components/options'
import { useT } from '../../../i18n'
import { useCreateCoupon } from '../hooks/use-coupons'
import { BENEFITS, createCouponSchema, toCouponRequest } from '../schema'

export function CreateCouponDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('coupons')
  const tc = useT('common')
  const form = useZodForm(createCouponSchema, {
    name: '',
    currency: 'USD',
    benefitType: 'PERCENT_OFF',
    value: '10',
    code: '',
    codeMaxRedemptions: '',
    maxRedemptions: '',
    maxPerCustomer: '',
    redeemBy: '',
    stackable: false,
  })
  const benefit = form.watch('benefitType')
  const currency = form.watch('currency')
  const create = useCreateCoupon(() => {
    onClose()
    form.reset()
  })
  const percent = benefit === 'PERCENT_OFF'
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('createTitle')}
      description={t('createHint')}
      submitLabel={tc('actions.create')}
      busy={create.isPending}
      form={form}
      size="lg"
      onSubmit={(values) => create.mutate(toCouponRequest(values))}
    >
      <FormField control={form.control} name="name" label={t('name')} autoFocus />
      <FormField
        control={form.control}
        name="currency"
        label={t('currency')}
        type="select"
        options={asOptions(CURRENCIES)}
      />
      <FormField
        control={form.control}
        name="benefitType"
        label={t('benefit')}
        type="select"
        options={asOptions(BENEFITS, (value) => t(`benefit.${value}`))}
      />
      <FormField
        control={form.control}
        name="value"
        label={percent ? t('percent') : t('amountOff')}
        hint={percent ? t('percentHint') : undefined}
        suffix={percent ? '%' : currency}
        inputMode="decimal"
        mono
      />
      <FormField control={form.control} name="code" label={t('code')} hint={t('codeHint')} mono optional />
      <FormField control={form.control} name="codeMaxRedemptions" label={t('codeMax')} type="number" min={1} optional />
      <FormField
        control={form.control}
        name="maxRedemptions"
        label={t('maxRedemptions')}
        hint={t('limitsHint')}
        type="number"
        min={1}
        optional
      />
      <FormField
        control={form.control}
        name="maxPerCustomer"
        label={t('maxPerCustomer')}
        type="number"
        min={1}
        optional
      />
      <FormField control={form.control} name="redeemBy" label={t('redeemBy')} type="datetime-utc" optional />
      <div className="self-end pb-1">
        <FormSwitch control={form.control} name="stackable" label={t('stackable')} />
      </div>
    </FormDialog>
  )
}
