import type { InvoiceResponse } from '@suite/api-client/billing'
import { ConfirmDialog, FormDialog, FormEntityPicker, useZodForm } from '@suite/remote-kit'
import { Field, Money } from '@suite/ui'
import { useEffect, useState } from 'react'
import { useT } from '../../../i18n'
import {
  useChargeOptions,
  useInvoiceCharge,
  useMarkUncollectible,
  useRecordPayment,
  useVoidInvoice,
} from '../hooks/use-invoices'
import { invoiceChargeSchema } from '../schema'

export function CreateInvoiceDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('invoices')
  const tc = useT('common')
  const form = useZodForm(invoiceChargeSchema, { chargeId: '' })
  const create = useInvoiceCharge(() => {
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
      onSubmit={(values) => create.mutate(values.chargeId)}
    >
      <FormEntityPicker
        control={form.control}
        name="chargeId"
        label={t('charge')}
        hint={t('chargeHint')}
        useOptions={useChargeOptions}
      />
    </FormDialog>
  )
}

export type InvoiceAction = 'pay' | 'uncollectible' | 'void'

/** The three decisions on an open invoice; each lists what it affects. */
export function InvoiceActionDialog({
  invoice,
  action,
  onClose,
}: {
  invoice: InvoiceResponse
  action: InvoiceAction | null
  onClose: () => void
}) {
  const t = useT('invoices')
  const tc = useT('common')
  const [reason, setReason] = useState('')
  useEffect(() => {
    if (action === null) setReason('')
  }, [action])
  const voidIt = useVoidInvoice(onClose)
  const writeOff = useMarkUncollectible(onClose)
  const record = useRecordPayment(onClose)
  const impact = [
    { label: t('number'), value: invoice.number ?? '' },
    {
      label: tc('fields.amount'),
      value: <Money minor={invoice.total?.amount ?? 0} currency={invoice.total?.currency} />,
    },
  ]
  const id = invoice.id ?? ''

  return (
    <>
      <ConfirmDialog
        open={action === 'void'}
        onClose={onClose}
        title={tc('confirm.voidInvoice')}
        description={tc('confirm.voidInvoiceHint')}
        confirmLabel={t('void')}
        busy={voidIt.isPending}
        impact={impact}
        onConfirm={() => voidIt.mutate({ id, reason: reason.trim() || undefined })}
      >
        <Field label={t('voidReason')} value={reason} onChange={(event) => setReason(event.target.value)} optional />
      </ConfirmDialog>
      <ConfirmDialog
        open={action === 'uncollectible'}
        onClose={onClose}
        title={tc('confirm.uncollectible')}
        description={tc('confirm.uncollectibleHint')}
        confirmLabel={t('uncollectible')}
        busy={writeOff.isPending}
        impact={impact}
        onConfirm={() => writeOff.mutate(id)}
      />
      <ConfirmDialog
        open={action === 'pay'}
        onClose={onClose}
        title={t('payTitle')}
        confirmLabel={t('pay')}
        destructive={false}
        busy={record.isPending}
        impact={impact}
        onConfirm={() => record.mutate(id)}
      />
    </>
  )
}
