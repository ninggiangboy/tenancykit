import { FormField } from '@suite/remote-kit'
import type { Control, FieldValues, Path } from 'react-hook-form'
import { useEnumLabel } from '../../../components/facets'
import { asOptions, BILLING_CYCLES } from '../../../components/options'
import { useT } from '../../../i18n'
import { MODEL_FIELDS, type PricingModel } from '../rule'

/** The cycle and the rule fields the chosen model reads; nothing else is shown. */
export function RuleFields<Values extends FieldValues>({
  control,
  model,
  currency,
}: {
  control: Control<Values, unknown, FieldValues>
  model: PricingModel
  currency: string
}) {
  const t = useT('plans')
  const enumLabel = useEnumLabel()
  const fields = MODEL_FIELDS[model]
  const name = (field: string) => field as Path<Values>
  const price = (field: 'unitPrice' | 'fixedPrice' | 'executionFee' | 'increment', label: string, optional = false) => (
    <FormField
      control={control}
      name={name(field)}
      label={label}
      hint={field === 'increment' ? undefined : t('priceHint')}
      suffix={field === 'increment' ? undefined : currency}
      optional={optional}
      inputMode="decimal"
      mono
    />
  )
  return (
    <>
      <FormField
        control={control}
        name={name('billingCycle')}
        label={t('billingCycle')}
        type="select"
        options={asOptions(BILLING_CYCLES, enumLabel)}
      />
      {fields.includes('fixedPrice') && price('fixedPrice', t('fixedPrice'))}
      {model === 'HYBRID' && <p className="text-body-strong text-foreground sm:col-span-2">{t('componentModel')}</p>}
      {fields.includes('metric') && (
        <FormField control={control} name={name('metric')} label={t('metric')} hint={t('metricHint')} mono />
      )}
      {fields.includes('unit') && (
        <FormField control={control} name={name('unit')} label={t('unit')} hint={t('unitHint')} mono />
      )}
      {fields.includes('unitPrice') && price('unitPrice', t('unitPrice'))}
      {fields.includes('increment') && price('increment', t('increment'), true)}
      {fields.includes('executionFee') && price('executionFee', t('executionFee'), true)}
    </>
  )
}
