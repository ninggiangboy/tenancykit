import { FormDialog, FormEntityPicker, FormField, rules, useZodForm } from '@suite/remote-kit'
import { z } from 'zod'
import { useEnumLabel } from '../../../components/facets'
import { useCustomerOptions } from '../../../components/lookups'
import { toMinor } from '../../../components/money'
import { asOptions, CURRENCIES, WALLET_MODES } from '../../../components/options'
import { useT } from '../../../i18n'
import { useOpenWallet } from '../hooks'

const schema = z.object({
  customerId: rules.id(),
  currency: rules.currency(),
  mode: z.enum(WALLET_MODES),
  creditLimit: z
    .string()
    .trim()
    .optional()
    .transform((value) => (value ? value : undefined))
    .pipe(
      z
        .string()
        .regex(/^\d+(\.\d+)?$/, { message: 'form.nonNegative' })
        .optional(),
    ),
})

export function OpenWalletDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('wallets')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const form = useZodForm(schema, { customerId: '', currency: 'USD', mode: 'PREPAID', creditLimit: '' })
  const mode = form.watch('mode')
  const currency = form.watch('currency')
  const openWallet = useOpenWallet(() => {
    onClose()
    form.reset()
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('createTitle')}
      description={t('createHint')}
      submitLabel={tc('actions.create')}
      busy={openWallet.isPending}
      form={form}
      onSubmit={(values) =>
        openWallet.mutate({
          customerId: values.customerId,
          currency: values.currency,
          mode: values.mode,
          creditLimit:
            values.mode === 'POSTPAID' && values.creditLimit ? toMinor(values.creditLimit, values.currency) : undefined,
        })
      }
    >
      <FormEntityPicker
        control={form.control}
        name="customerId"
        label={tc('picker.customer')}
        useOptions={useCustomerOptions}
      />
      <FormField
        control={form.control}
        name="currency"
        label={t('currency')}
        type="select"
        options={asOptions(CURRENCIES)}
      />
      <FormField
        control={form.control}
        name="mode"
        label={t('mode')}
        hint={t('modeHint')}
        type="select"
        options={asOptions(WALLET_MODES, enumLabel)}
      />
      {mode === 'POSTPAID' && (
        <FormField
          control={form.control}
          name="creditLimit"
          label={t('creditLimit')}
          hint={t('creditLimitHint')}
          suffix={currency}
          inputMode="decimal"
          optional
          mono
        />
      )}
    </FormDialog>
  )
}
