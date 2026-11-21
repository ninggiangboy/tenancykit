import type { ReservationResponse } from '@suite/api-client/billing'
import { ConfirmDialog, FormDialog, FormEntityPicker, FormField, rules, useZodForm } from '@suite/remote-kit'
import { Money } from '@suite/ui'
import { useEffect } from 'react'
import { z } from 'zod'
import { useWallet, useWalletOptions } from '../../../components/lookups'
import { amount, fromMinor, toMinor } from '../../../components/money'
import { useT } from '../../../i18n'
import { useCapture, useRelease, useReserve } from '../hooks'

const reserveSchema = z.object({
  walletId: rules.id(),
  amount: amount(),
  currency: rules.currency(),
  ttlSeconds: rules.optionalCount(),
  reference: rules.optionalText(),
})
const captureSchema = z.object({ amount: amount(), reference: rules.optionalText() })
const useAnyWallet = (search: string) => useWalletOptions(search)

export function ReserveDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('reservations')
  const tc = useT('common')
  const form = useZodForm(reserveSchema, { walletId: '', amount: '', currency: 'USD', ttlSeconds: '', reference: '' })
  const wallet = useWallet(form.watch('walletId'))
  const { setValue } = form
  useEffect(() => {
    if (wallet.data?.currency) setValue('currency', wallet.data.currency)
  }, [wallet.data?.currency, setValue])
  const reserve = useReserve(() => {
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
      action={reserve}
      form={form}
      onSubmit={(values) => reserve.mutate({ ...values, amount: toMinor(values.amount, values.currency) })}
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
      <FormField
        control={form.control}
        name="ttlSeconds"
        label={t('ttl')}
        hint={t('ttlHint')}
        type="number"
        min={1}
        optional
      />
      <FormField control={form.control} name="reference" label={t('reference')} mono optional />
    </FormDialog>
  )
}

interface Props {
  reservation: ReservationResponse
  open: boolean
  onClose: () => void
}

export function CaptureDialog({ reservation, open, onClose }: Props) {
  const t = useT('reservations')
  const currency = reservation.amount?.currency ?? 'USD'
  const held = reservation.amount?.amount ?? 0
  const form = useZodForm(captureSchema, { amount: fromMinor(held, currency), reference: reservation.reference ?? '' })
  const { reset, setError } = form
  useEffect(() => {
    if (open) reset({ amount: fromMinor(held, currency), reference: reservation.reference ?? '' })
  }, [open, held, currency, reservation.reference, reset])
  const captureIt = useCapture(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('capture')}
      description={t('captureHint')}
      submitLabel={t('capture')}
      action={captureIt}
      form={form}
      onSubmit={(values) => {
        const minor = toMinor(values.amount, currency)
        if (minor > held) {
          setError('amount', { message: t('overHeld') })
          return
        }
        captureIt.mutate({ id: reservation.id ?? '', amount: minor, currency, reference: values.reference })
      }}
    >
      <FormField
        control={form.control}
        name="amount"
        label={t('amount')}
        suffix={currency}
        inputMode="decimal"
        mono
        autoFocus
      />
      <FormField control={form.control} name="reference" label={t('reference')} mono optional />
    </FormDialog>
  )
}

export function ReleaseDialog({ reservation, open, onClose }: Props) {
  const t = useT('reservations')
  const tc = useT('common')
  const releaseIt = useRelease(onClose)
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      title={tc('confirm.releaseReservation')}
      description={tc('confirm.releaseReservationHint')}
      confirmLabel={t('release')}
      action={releaseIt}
      onConfirm={() => releaseIt.mutate(reservation.id ?? '')}
      impact={[
        {
          label: tc('fields.amount'),
          value: <Money minor={reservation.amount?.amount ?? 0} currency={reservation.amount?.currency} />,
        },
      ]}
    />
  )
}
