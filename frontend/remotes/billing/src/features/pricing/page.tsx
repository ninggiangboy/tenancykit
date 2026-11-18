import { permissions } from '@suite/api-client'
import type { EstimateResponse, RatingResponse, ReserveResponse } from '@suite/api-client/billing'
import { chargeUsage, estimate, reserveEstimate } from '@suite/api-client/billing'
import { usePermission } from '@suite/shell-sdk/react'
import { Alert, Button, Card, Field, Money, Tabs, type UrlState } from '@suite/ui'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useT } from '../../i18n'
import { DetailRow, DetailRows, Forbidden } from '../../lib/page'
import { errorMessage, useApi } from '../../lib/runtime'

type Mode = 'estimate' | 'usage' | 'reservation'

export function PricingPage({ state }: { state: UrlState }) {
  const t = useT('pricing')
  const canRead = usePermission(permissions.billing.estimate)
  const [mode, setMode] = useState<Mode>((state.get('mode') as Mode | null) ?? 'estimate')

  if (!canRead) return <Forbidden />

  return (
    <div className="flex flex-col gap-4 p-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-title text-foreground">{t('title')}</h1>
        <p className="max-w-2xl text-small text-muted-foreground">{t('description')}</p>
      </div>
      <Tabs
        items={[
          { id: 'estimate', label: t('estimate') },
          { id: 'usage', label: t('usageCharge') },
          { id: 'reservation', label: t('reservation') },
        ]}
        value={mode}
        onChange={(id) => {
          setMode(id as Mode)
          state.set({ mode: id })
        }}
        label={t('title')}
      />
      {mode === 'estimate' && <EstimatePanel />}
      {mode === 'usage' && <UsageChargePanel />}
      {mode === 'reservation' && <ReservationPanel />}
    </div>
  )
}

function EstimatePanel() {
  const t = useT('pricing')
  const tc = useT('common')
  const { billing } = useApi()
  const [form, setForm] = useState({ planId: '', version: '', walletId: '', at: '', metric: '', quantity: '' })
  const run = useMutation({
    mutationFn: async () =>
      (
        await estimate({
          client: billing,
          throwOnError: true,
          body: {
            planId: form.planId.trim(),
            version: form.version ? Number(form.version) : undefined,
            walletId: form.walletId.trim() || undefined,
            at: form.at ? new Date(form.at).toISOString() : undefined,
            usage: form.metric ? [{ metric: form.metric.trim(), quantity: form.quantity.trim() || '1' }] : [],
          },
        })
      ).data,
  })

  return (
    <div className="flex flex-col gap-3">
      <Card title={t('estimate')} description={t('estimateHint')}>
        <div className="grid grid-cols-2 gap-3">
          <Field
            label={t('planId')}
            value={form.planId}
            onChange={(e) => setForm({ ...form, planId: e.target.value })}
            mono
            required
          />
          <Field
            label={t('version')}
            type="number"
            value={form.version}
            onChange={(e) => setForm({ ...form, version: e.target.value })}
          />
          <Field
            label={t('walletId')}
            value={form.walletId}
            onChange={(e) => setForm({ ...form, walletId: e.target.value })}
            mono
            optional
          />
          <Field
            label={t('at')}
            type="datetime-local"
            value={form.at}
            onChange={(e) => setForm({ ...form, at: e.target.value })}
            optional
          />
          <Field
            label={t('metric')}
            value={form.metric}
            onChange={(e) => setForm({ ...form, metric: e.target.value })}
            mono
            optional
          />
          <Field
            label={t('quantity')}
            value={form.quantity}
            onChange={(e) => setForm({ ...form, quantity: e.target.value })}
            optional
          />
        </div>
        <div className="mt-3 flex justify-end">
          <Button loading={run.isPending} onClick={() => run.mutate()}>
            {t('estimate')}
          </Button>
        </div>
        {run.error && (
          <Alert tone="danger" title={tc('state.error')}>
            {errorMessage(run.error)}
          </Alert>
        )}
      </Card>
      {run.data && <EstimateResult estimate={run.data} />}
    </div>
  )
}

function EstimateResult({ estimate: result }: { estimate: EstimateResponse }) {
  const t = useT('pricing')
  const tc = useT('common')
  return (
    <Card title={t('total')}>
      <DetailRows>
        <DetailRow label={t('total')}>
          <Money minor={result.amount?.amount ?? 0} currency={result.amount?.currency} />
        </DetailRow>
        <DetailRow label={t('gross')}>
          <Money minor={result.grossAmount?.amount ?? 0} currency={result.grossAmount?.currency} />
        </DetailRow>
        <DetailRow label={t('planId')}>{result.planId}</DetailRow>
        <DetailRow label={t('version')}>{result.planVersion}</DetailRow>
      </DetailRows>
      <div className="mt-3 flex flex-col gap-1">
        <h3 className="text-body-strong text-foreground">{t('discounts')}</h3>
        {result.discounts && result.discounts.length > 0 ? (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {result.discounts.map((discount) => (
              <li key={discount.redemptionId} className="flex justify-between gap-2 px-3 py-2 text-small">
                <span>{discount.couponName ?? discount.couponId}</span>
                <Money minor={discount.amount?.amount ?? 0} currency={discount.amount?.currency} />
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-small text-muted-foreground">{tc('state.none')}</p>
        )}
      </div>
      <div className="mt-3 flex flex-col gap-1">
        <h3 className="text-body-strong text-foreground">{t('lines')}</h3>
        <ul className="divide-y divide-border rounded-lg border border-border">
          {(result.lines ?? []).map((line) => (
            <li key={`${line.metric}:${line.description}`} className="flex justify-between gap-2 px-3 py-2 text-small">
              <span>
                {line.description} · {line.metric} · {line.quantity}
              </span>
              <span className="font-mono text-code">{line.amount}</span>
            </li>
          ))}
        </ul>
      </div>
    </Card>
  )
}

function UsageChargePanel() {
  const t = useT('pricing')
  const tc = useT('common')
  const { billing } = useApi()
  const [form, setForm] = useState({ customerId: '', walletId: '', planId: '', version: '', from: '', to: '' })
  const run = useMutation({
    mutationFn: async () =>
      (
        await chargeUsage({
          client: billing,
          throwOnError: true,
          body: {
            customerId: form.customerId.trim() || undefined,
            walletId: form.walletId.trim() || undefined,
            planId: form.planId.trim() || undefined,
            version: form.version ? Number(form.version) : undefined,
            from: form.from ? new Date(form.from).toISOString() : undefined,
            to: form.to ? new Date(form.to).toISOString() : undefined,
          },
        })
      ).data,
  })
  return (
    <div className="flex flex-col gap-3">
      <Card title={t('usageCharge')} description={t('usageChargeHint')}>
        <div className="grid grid-cols-2 gap-3">
          <Field
            label={t('customerId')}
            value={form.customerId}
            onChange={(e) => setForm({ ...form, customerId: e.target.value })}
            mono
            optional
          />
          <Field
            label={t('walletId')}
            value={form.walletId}
            onChange={(e) => setForm({ ...form, walletId: e.target.value })}
            mono
            optional
          />
          <Field
            label={t('planId')}
            value={form.planId}
            onChange={(e) => setForm({ ...form, planId: e.target.value })}
            mono
            optional
          />
          <Field
            label={t('version')}
            type="number"
            value={form.version}
            onChange={(e) => setForm({ ...form, version: e.target.value })}
          />
          <Field
            label={t('from')}
            type="datetime-local"
            value={form.from}
            onChange={(e) => setForm({ ...form, from: e.target.value })}
            optional
          />
          <Field
            label={t('to')}
            type="datetime-local"
            value={form.to}
            onChange={(e) => setForm({ ...form, to: e.target.value })}
            optional
          />
        </div>
        <div className="mt-3 flex justify-end">
          <Button loading={run.isPending} onClick={() => run.mutate()}>
            {t('usageCharge')}
          </Button>
        </div>
        {run.error && (
          <Alert tone="danger" title={tc('state.error')}>
            {errorMessage(run.error)}
          </Alert>
        )}
      </Card>
      {run.data && <RatingResult rating={run.data} />}
    </div>
  )
}

function RatingResult({ rating }: { rating: RatingResponse }) {
  const t = useT('pricing')
  return (
    <Card title={t('outcome')}>
      <DetailRows>
        <DetailRow label={t('outcome')}>{rating.outcome}</DetailRow>
        <DetailRow label={t('total')}>
          <Money minor={rating.amount?.amount ?? 0} currency={rating.amount?.currency} />
        </DetailRow>
        <DetailRow label={t('from')}>{rating.periodStart}</DetailRow>
        <DetailRow label={t('to')}>{rating.periodEnd}</DetailRow>
        <DetailRow label={t('failureReason')}>{rating.failureReason ?? ''}</DetailRow>
        <DetailRow label={t('lines')}>{rating.lines?.length ?? 0}</DetailRow>
      </DetailRows>
    </Card>
  )
}

function ReservationPanel() {
  const t = useT('pricing')
  const tc = useT('common')
  const { billing } = useApi()
  const [form, setForm] = useState({ walletId: '', amount: '', currency: 'USD' })
  const run = useMutation({
    mutationFn: async () =>
      (
        await reserveEstimate({
          client: billing,
          throwOnError: true,
          headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
          body: {
            walletId: form.walletId.trim(),
            amount: Number(form.amount),
            currency: form.currency.trim().toUpperCase(),
          },
        })
      ).data,
  })
  return (
    <div className="flex flex-col gap-3">
      <Card title={t('reservation')} description={t('reservationHint')}>
        <div className="grid grid-cols-2 gap-3">
          <Field
            label={t('walletId')}
            value={form.walletId}
            onChange={(e) => setForm({ ...form, walletId: e.target.value })}
            mono
            required
          />
          <Field
            label={tc('fields.amount')}
            type="number"
            value={form.amount}
            onChange={(e) => setForm({ ...form, amount: e.target.value })}
          />
          <Field
            label={tc('fields.currency')}
            value={form.currency}
            onChange={(e) => setForm({ ...form, currency: e.target.value })}
            mono
          />
        </div>
        <div className="mt-3 flex justify-end">
          <Button loading={run.isPending} onClick={() => run.mutate()}>
            {t('reservation')}
          </Button>
        </div>
        {run.error && (
          <Alert tone="danger" title={tc('state.error')}>
            {errorMessage(run.error)}
          </Alert>
        )}
      </Card>
      {run.data && <ReservationResult result={run.data} />}
    </div>
  )
}

function ReservationResult({ result }: { result: ReserveResponse }) {
  const t = useT('pricing')
  return (
    <Card title={t('outcome')}>
      <DetailRows>
        <DetailRow label={t('total')}>
          <Money minor={result.estimate?.amount?.amount ?? 0} currency={result.estimate?.amount?.currency} />
        </DetailRow>
        <DetailRow label={t('reservation')}>{result.reservation?.status ?? ''}</DetailRow>
      </DetailRows>
    </Card>
  )
}
