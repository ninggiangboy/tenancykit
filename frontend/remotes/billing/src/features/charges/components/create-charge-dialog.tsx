import { FormDialog, FormEntityPicker, FormField, rules, useZodForm } from '@suite/remote-kit'
import { Alert } from '@suite/ui'
import { useEffect } from 'react'
import { z } from 'zod'
import { useWallet, useWalletOptions } from '../../../components/lookups'
import { amount, toMinor } from '../../../components/money'
import { useT } from '../../../i18n'
import { useCreateCharge } from '../hooks'

const schema = z.object({
  walletId: rules.id(),
  amount: amount(),
  currency: rules.currency(),
  reference: rules.optionalText(),
  usageRef: rules.optionalText(),
})
const useAnyWallet = (search: string) => useWalletOptions(search)

export function CreateChargeDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('charges')
  const tc = useT('common')
  const form = useZodForm(schema, { walletId: '', amount: '', currency: 'USD', reference: '', usageRef: '' })
  const wallet = useWallet(form.watch('walletId'))
  const { setValue } = form
  useEffect(() => {
    if (wallet.data?.currency) setValue('currency', wallet.data.currency)
  }, [wallet.data?.currency, setValue])
  const create = useCreateCharge(() => {
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
      busy={create.isPending}
      form={form}
      onSubmit={(values) => create.mutate({ ...values, amount: toMinor(values.amount, values.currency) })}
    >
      <FormEntityPicker control={form.control} name="walletId" label={tc('picker.wallet')} useOptions={useAnyWallet} />
      <FormField
        control={form.control}
        name="amount"
        label={t('amount')}
        suffix={form.watch('currency')}
        inputMode="decimal"
        mono
      />
      <FormField control={form.control} name="reference" label={t('reference')} mono optional />
      <FormField control={form.control} name="usageRef" label={t('usageRef')} mono optional />
      <Alert tone="warning">{t('confirmHint')}</Alert>
    </FormDialog>
  )
}
