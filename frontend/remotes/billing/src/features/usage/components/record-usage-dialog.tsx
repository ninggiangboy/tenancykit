import { FormDialog, FormEntityPicker, FormField, rules, useZodForm } from '@suite/remote-kit'
import { parseUtcInput } from '@suite/ui'
import { z } from 'zod'
import { useCustomerOptions } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useRecordUsage } from '../hooks'

const schema = z.object({
  customerId: rules.id(),
  metric: rules.text(),
  quantity: z
    .string()
    .trim()
    .regex(/^\d+(\.\d+)?$/, { message: 'form.invalid' }),
  unit: rules.optionalText(),
  occurredAt: rules
    .optionalText()
    .refine((value) => !value || parseUtcInput(value) !== null, { message: 'errors.utcFormat' })
    .transform((value) => (value ? (parseUtcInput(value) as string) : undefined)),
  resourceRef: rules.optionalText(),
})

export function RecordUsageDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('usage')
  const tc = useT('common')
  const form = useZodForm(schema, {
    customerId: '',
    metric: '',
    quantity: '',
    unit: '',
    occurredAt: '',
    resourceRef: '',
  })
  const record = useRecordUsage(() => {
    onClose()
    form.reset()
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('recordTitle')}
      description={t('recordHint')}
      submitLabel={t('record')}
      busy={record.isPending}
      form={form}
      size="lg"
      onSubmit={(values) => record.mutate(values)}
    >
      <FormEntityPicker
        control={form.control}
        name="customerId"
        label={tc('picker.customer')}
        useOptions={useCustomerOptions}
      />
      <FormField control={form.control} name="metric" label={t('metric')} mono />
      <FormField
        control={form.control}
        name="quantity"
        label={t('quantity')}
        hint={t('quantityHint')}
        inputMode="decimal"
        mono
      />
      <FormField control={form.control} name="unit" label={t('unit')} mono optional />
      <FormField control={form.control} name="occurredAt" label={t('occurredAt')} type="datetime-utc" optional />
      <FormField control={form.control} name="resourceRef" label={t('resourceRef')} mono optional />
    </FormDialog>
  )
}
