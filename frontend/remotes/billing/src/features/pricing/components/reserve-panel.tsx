import type { ReserveResponse } from '@suite/api-client/billing'
import { reserveEstimate } from '@suite/api-client/billing'
import {
  ConfirmDialog,
  DetailRow,
  DetailRows,
  FormEntityPicker,
  FormField,
  idempotencyKey,
  useAction,
  useApi,
  useZodForm,
} from '@suite/remote-kit'
import { Card, Money, StatusBadge } from '@suite/ui'
import { useEffect, useState } from 'react'
import { useWallet, useWalletOptions } from '../../../components/lookups'
import { toMinor } from '../../../components/money'
import { useT } from '../../../i18n'
import { reserveSchema } from '../schema'
import { PanelForm } from './panel-form'

type Values = ReturnType<typeof reserveSchema.parse>
const useAnyWallet = (search: string) => useWalletOptions(search)

/** Holds funds on a wallet; the form stages the hold and the confirmation places it. */
export function ReservePanel() {
  const t = useT('pricing')
  const tc = useT('common')
  const { billing } = useApi()
  const form = useZodForm(reserveSchema, { walletId: '', amount: '', currency: 'USD', ttlSeconds: '', reference: '' })
  const wallet = useWallet(form.watch('walletId'))
  const { setValue } = form
  useEffect(() => {
    if (wallet.data?.currency) setValue('currency', wallet.data.currency)
  }, [wallet.data?.currency, setValue])
  const [staged, setStaged] = useState<Values | null>(null)
  const [result, setResult] = useState<ReserveResponse | null>(null)
  const run = useAction(
    (values: Values) =>
      reserveEstimate({
        client: billing,
        throwOnError: true,
        headers: idempotencyKey(),
        body: { ...values, amount: toMinor(values.amount, values.currency) },
      }).then((answer) => answer.data),
    {
      onSuccess: (answer) => {
        setStaged(null)
        setResult(answer)
      },
      successToast: t('reserved'),
    },
  )
  const currency = form.watch('currency')

  return (
    <div className="flex flex-col gap-4">
      <PanelForm
        title={t('reservation')}
        description={t('reservationHint')}
        form={form}
        onSubmit={setStaged}
        submitLabel={t('reserveRun')}
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
          suffix={currency}
          inputMode="decimal"
          mono
        />
        <FormField control={form.control} name="ttlSeconds" label={t('ttl')} type="number" min={1} optional />
        <FormField control={form.control} name="reference" label={t('reference')} mono optional />
      </PanelForm>
      <ConfirmDialog
        open={staged !== null}
        onClose={() => setStaged(null)}
        title={t('confirmReserve')}
        description={t('confirmReserveHint')}
        confirmLabel={t('reserveRun')}
        destructive={false}
        action={run}
        onConfirm={() => staged && run.mutate(staged)}
        impact={[
          {
            label: tc('fields.amount'),
            value: staged ? <Money minor={toMinor(staged.amount, staged.currency)} currency={staged.currency} /> : '',
          },
        ]}
      />
      {result && (
        <Card title={t('outcome')}>
          <DetailRows>
            <DetailRow label={tc('fields.amount')}>
              <Money minor={result.estimate?.amount?.amount ?? 0} currency={result.estimate?.amount?.currency} />
            </DetailRow>
            <DetailRow label={t('reservation')}>
              <StatusBadge status={result.reservation?.status} />
            </DetailRow>
          </DetailRows>
        </Card>
      )}
    </div>
  )
}
