import type { RatingResponse } from '@suite/api-client/billing'
import { chargeUsage } from '@suite/api-client/billing'
import {
  ConfirmDialog,
  DetailRow,
  DetailRows,
  FormEntityPicker,
  FormField,
  Maybe,
  Timestamp,
  useAction,
  useApi,
  useZodForm,
} from '@suite/remote-kit'
import { Alert, Card, Money, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useCustomerOptions, usePlanOptions, useWalletOptions } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { usageChargeSchema } from '../schema'
import { PanelForm } from './panel-form'

type Values = ReturnType<typeof usageChargeSchema.parse>

/** Charges real money, so the form only stages the request; the confirmation runs it. */
export function UsageChargePanel() {
  const t = useT('pricing')
  const tc = useT('common')
  const { billing } = useApi()
  const form = useZodForm(usageChargeSchema, {
    customerId: '',
    walletId: '',
    planId: '',
    version: '',
    from: '',
    to: '',
  })
  const customerId = form.watch('customerId') || undefined
  const useWallets = (search: string) => useWalletOptions(search, customerId)
  const [staged, setStaged] = useState<Values | null>(null)
  const [result, setResult] = useState<RatingResponse | null>(null)
  const run = useAction(
    (values: Values) =>
      chargeUsage({ client: billing, throwOnError: true, body: values }).then((answer) => answer.data),
    {
      onSuccess: (answer) => {
        setStaged(null)
        setResult(answer)
      },
      successToast: (answer) => (answer.replayed ? t('replayed') : t('charged')),
    },
  )

  return (
    <div className="flex flex-col gap-4">
      <PanelForm
        title={t('usageCharge')}
        description={t('usageChargeHint')}
        form={form}
        onSubmit={setStaged}
        submitLabel={t('chargeRun')}
      >
        <FormEntityPicker
          control={form.control}
          name="customerId"
          label={tc('picker.customer')}
          optional
          useOptions={useCustomerOptions}
        />
        <FormEntityPicker
          control={form.control}
          name="walletId"
          label={tc('picker.wallet')}
          optional
          useOptions={useWallets}
        />
        <FormEntityPicker
          control={form.control}
          name="planId"
          label={tc('picker.plan')}
          optional
          useOptions={usePlanOptions}
        />
        <FormField
          control={form.control}
          name="version"
          label={t('version')}
          hint={t('versionHint')}
          type="number"
          min={1}
          optional
        />
        <FormField control={form.control} name="from" label={t('from')} type="datetime-utc" optional />
        <FormField control={form.control} name="to" label={t('to')} type="datetime-utc" optional />
      </PanelForm>
      <ConfirmDialog
        open={staged !== null}
        onClose={() => setStaged(null)}
        title={t('confirmCharge')}
        description={t('confirmChargeHint')}
        confirmLabel={t('chargeRun')}
        busy={run.isPending}
        onConfirm={() => staged && run.mutate(staged)}
        impact={[
          { label: t('from'), value: <Timestamp value={staged?.from} /> },
          { label: t('to'), value: <Timestamp value={staged?.to} /> },
        ]}
      />
      {result && (
        <Card title={t('outcome')}>
          {result.failureReason && <Alert tone="danger">{result.failureReason}</Alert>}
          <DetailRows>
            <DetailRow label={t('outcome')}>
              <StatusBadge status={result.outcome} />
            </DetailRow>
            <DetailRow label={t('total')}>
              <Money minor={result.amount?.amount ?? 0} currency={result.amount?.currency} />
            </DetailRow>
            <DetailRow label={tc('fields.period')}>
              <Timestamp value={result.periodStart} /> – <Timestamp value={result.periodEnd} />
            </DetailRow>
            <DetailRow label={t('lines')}>
              <Maybe value={result.lines?.length} />
            </DetailRow>
          </DetailRows>
        </Card>
      )}
    </div>
  )
}
