import type { PaymentResponse } from '@suite/api-client/billing'
import { FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { DialogImpact, Money } from '@suite/ui'
import { useEffect } from 'react'
import { fromMinor, toMinor } from '../../../components/money'
import { useT } from '../../../i18n'
import { useRefundPayment } from '../hooks/use-payments'
import { refundSchema } from '../schema'

export function RefundDialog({
  payment,
  open,
  onClose,
}: {
  payment: PaymentResponse
  open: boolean
  onClose: () => void
}) {
  const t = useT('payments')
  const tc = useT('common')
  const currency = payment.amount?.currency ?? 'USD'
  const refundable = (payment.amount?.amount ?? 0) - (payment.refunded?.amount ?? 0)
  const form = useZodForm(refundSchema, { amount: fromMinor(refundable, currency), reason: '' })
  const { reset, setError } = form
  useEffect(() => {
    if (open) reset({ amount: fromMinor(refundable, currency), reason: '' })
  }, [open, refundable, currency, reset])
  const refund = useRefundPayment(onClose)

  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('refundTitle')}
      description={tc('confirm.refundHint')}
      submitLabel={t('refund')}
      busy={refund.isPending}
      form={form}
      onSubmit={(values) => {
        const minor = toMinor(values.amount, currency)
        if (minor > refundable) {
          setError('amount', { message: t('overRefundable') })
          return
        }
        refund.mutate({ paymentId: payment.id ?? '', amount: minor, currency, reason: values.reason })
      }}
    >
      <DialogImpact
        items={[
          { label: tc('fields.amount'), value: <Money minor={payment.amount?.amount ?? 0} currency={currency} /> },
          { label: t('refundable'), value: <Money minor={refundable} currency={currency} /> },
        ]}
      />
      <FormField
        control={form.control}
        name="amount"
        label={t('refundAmount')}
        hint={t('amountHint', { currency })}
        suffix={currency}
        inputMode="decimal"
        mono
      />
      <FormField control={form.control} name="reason" label={tc('fields.reason')} optional />
    </FormDialog>
  )
}
