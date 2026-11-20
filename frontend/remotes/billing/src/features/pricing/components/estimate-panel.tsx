import type { EstimateResponse } from '@suite/api-client/billing'
import { estimate } from '@suite/api-client/billing'
import {
  DetailRow,
  DetailRows,
  errorMessage,
  FormEntityPicker,
  FormField,
  Section,
  useApi,
  useZodForm,
} from '@suite/remote-kit'
import { Alert, Card, Money } from '@suite/ui'
import { useMutation } from '@tanstack/react-query'
import { usePlanOptions, useWalletOptions } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { estimateSchema } from '../schema'
import { PanelForm } from './panel-form'

const useAnyWallet = (search: string) => useWalletOptions(search)

/** A pure calculation: nothing is stored. */
export function EstimatePanel() {
  const t = useT('pricing')
  const tc = useT('common')
  const { billing } = useApi()
  const form = useZodForm(estimateSchema, { planId: '', version: '', walletId: '', at: '', metric: '', quantity: '' })
  const run = useMutation({
    mutationFn: async (values: ReturnType<typeof estimateSchema.parse>) =>
      (
        await estimate({
          client: billing,
          throwOnError: true,
          body: {
            planId: values.planId,
            version: values.version,
            walletId: values.walletId,
            at: values.at,
            usage: values.metric ? [{ metric: values.metric, quantity: values.quantity ?? '1' }] : [],
          },
        })
      ).data,
  })

  return (
    <div className="flex flex-col gap-4">
      <PanelForm
        title={t('estimate')}
        description={t('estimateHint')}
        form={form}
        onSubmit={(values) => run.mutate(values)}
        submitLabel={t('estimate')}
        busy={run.isPending}
      >
        <FormEntityPicker
          control={form.control}
          name="planId"
          label={tc('picker.plan')}
          hint={t('planHint')}
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
        <FormEntityPicker
          control={form.control}
          name="walletId"
          label={tc('picker.wallet')}
          optional
          useOptions={useAnyWallet}
        />
        <FormField control={form.control} name="at" label={t('at')} type="datetime-local" optional />
        <FormField control={form.control} name="metric" label={t('metric')} mono optional />
        <FormField control={form.control} name="quantity" label={t('quantity')} inputMode="decimal" optional />
      </PanelForm>
      {run.error && (
        <Alert tone="danger" title={tc('state.error')}>
          {errorMessage(run.error)}
        </Alert>
      )}
      {run.data && <EstimateResult result={run.data} />}
    </div>
  )
}

function EstimateResult({ result }: { result: EstimateResponse }) {
  const t = useT('pricing')
  const tc = useT('common')
  const discounts = result.discounts ?? []
  const lines = result.lines ?? []
  return (
    <Card title={t('total')}>
      <div className="flex flex-col gap-4">
        <DetailRows>
          <DetailRow label={t('total')}>
            <Money minor={result.amount?.amount ?? 0} currency={result.amount?.currency} />
          </DetailRow>
          {result.grossAmount && (
            <DetailRow label={t('gross')}>
              <Money minor={result.grossAmount.amount ?? 0} currency={result.grossAmount.currency} />
            </DetailRow>
          )}
          <DetailRow label={tc('fields.plan')}>
            {result.planCode ?? result.planId} · {t('version')} {result.planVersion}
          </DetailRow>
        </DetailRows>
        {discounts.length > 0 && (
          <Section title={t('discounts')}>
            <ul className="divide-y divide-border rounded-lg border border-border">
              {discounts.map((discount) => (
                <li key={discount.redemptionId} className="flex justify-between gap-2 px-3 py-2 text-small">
                  <span>{discount.couponName ?? discount.couponId}</span>
                  <Money minor={-(discount.amount?.amount ?? 0)} currency={discount.amount?.currency} />
                </li>
              ))}
            </ul>
          </Section>
        )}
        {lines.length > 0 && (
          <Section title={t('lines')}>
            <ul className="divide-y divide-border rounded-lg border border-border">
              {lines.map((line) => (
                <li
                  key={`${line.metric}:${line.description}`}
                  className="flex justify-between gap-2 px-3 py-2 text-small"
                >
                  <span className="min-w-0 truncate">
                    {line.description} · <span className="font-mono">{line.metric}</span> × {line.quantity}
                  </span>
                  <span className="font-mono text-code">{line.amount}</span>
                </li>
              ))}
            </ul>
          </Section>
        )}
      </div>
    </Card>
  )
}
