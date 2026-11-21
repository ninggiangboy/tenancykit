import { FormDialog, FormEntityPicker, rules, useZodForm } from '@suite/remote-kit'
import { z } from 'zod'
import { useCustomerOptions, usePlanOptions, useWalletOptions } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useSubscribe } from '../hooks/use-subscriptions'

const schema = z.object({ customerId: rules.optionalText(), walletId: rules.id(), planId: rules.id() })

/** Customer first (optional, it narrows the wallets), then the wallet and the plan. */
export function SubscribeDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('subscriptions')
  const tc = useT('common')
  const form = useZodForm(schema, { customerId: '', walletId: '', planId: '' })
  const customerId = form.watch('customerId') || undefined
  const useWallets = (search: string) => useWalletOptions(search, customerId)
  const subscribe = useSubscribe(() => {
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
      action={subscribe}
      form={form}
      onSubmit={(values) => subscribe.mutate({ walletId: values.walletId, planId: values.planId })}
    >
      <FormEntityPicker
        control={form.control}
        name="customerId"
        label={tc('picker.customer')}
        hint={t('customerHint')}
        optional
        useOptions={useCustomerOptions}
      />
      <FormEntityPicker control={form.control} name="walletId" label={tc('picker.wallet')} useOptions={useWallets} />
      <FormEntityPicker control={form.control} name="planId" label={tc('picker.plan')} useOptions={usePlanOptions} />
    </FormDialog>
  )
}
