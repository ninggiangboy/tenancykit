import { FormDialog, FormEntityPicker, FormField, useZodForm } from '@suite/remote-kit'
import { Button, Dialog } from '@suite/ui'
import { useEffect, useState } from 'react'
import { useWallet, useWalletOptions } from '../../../components/lookups'
import { toMinor } from '../../../components/money'
import { useT } from '../../../i18n'
import { useCreateTopUp } from '../hooks/use-payments'
import { topUpSchema } from '../schema'

const useAnyWallet = (search: string) => useWalletOptions(search)

/** A top-up for a wallet: the currency follows the wallet, and the answer is a checkout link to hand on. */
export function TopUpDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('payments')
  const tc = useT('common')
  const [checkout, setCheckout] = useState<string | null>(null)
  const form = useZodForm(topUpSchema, { walletId: '', amount: '', currency: 'USD' })
  const walletId = form.watch('walletId')
  const wallet = useWallet(walletId)
  const currency = wallet.data?.currency
  const { setValue } = form
  useEffect(() => {
    if (currency) setValue('currency', currency)
  }, [currency, setValue])
  const topUp = useCreateTopUp((url) => {
    onClose()
    form.reset()
    setCheckout(url)
  })

  return (
    <>
      <FormDialog
        open={open}
        onClose={onClose}
        title={t('topUpTitle')}
        description={t('topUpHint')}
        submitLabel={t('topUp')}
        busy={topUp.isPending}
        form={form}
        onSubmit={(values) =>
          topUp.mutate({
            walletId: values.walletId,
            amount: toMinor(values.amount, values.currency),
            currency: values.currency,
          })
        }
      >
        <FormEntityPicker
          control={form.control}
          name="walletId"
          label={tc('picker.wallet')}
          useOptions={useAnyWallet}
        />
        <FormField
          control={form.control}
          name="amount"
          label={tc('fields.amount')}
          suffix={form.watch('currency')}
          inputMode="decimal"
          mono
        />
      </FormDialog>
      <Dialog
        open={Boolean(checkout)}
        onClose={() => setCheckout(null)}
        title={t('checkoutTitle')}
        description={t('checkoutHint')}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setCheckout(null)}>
              {tc('actions.close')}
            </Button>
            <Button
              size="sm"
              iconEnd="arrow-up-right"
              onClick={() => checkout && globalThis.open(checkout, '_blank', 'noopener')}
            >
              {t('openCheckout')}
            </Button>
          </>
        }
      >
        <code className="block break-all rounded-md border border-border bg-muted p-3 font-mono text-code">
          {checkout}
        </code>
      </Dialog>
    </>
  )
}
