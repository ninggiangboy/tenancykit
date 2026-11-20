import type { TotalResponse } from '@suite/api-client/billing'
import { ErrorPanel, FormEntityPicker, FormField, rules, useZodForm } from '@suite/remote-kit'
import { Button, Card, ChartFrame, DataTable, EmptyState, formatUtcInput, parseUtcInput } from '@suite/ui'
import { useId } from 'react'
import { Bar, BarChart, CartesianGrid, Tooltip as ChartTooltip, ResponsiveContainer, XAxis, YAxis } from 'recharts'
import { z } from 'zod'
import { useCustomerOptions } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useAggregates } from '../hooks'

const utcInstant = () =>
  rules
    .text()
    .refine((value) => parseUtcInput(value) !== null, { message: 'errors.utcFormat' })
    .transform((value) => parseUtcInput(value) as string)

const schema = z.object({
  customerId: rules.id(),
  metric: rules.optionalText(),
  from: utcInstant(),
  to: utcInstant(),
})

export function AggregatesPanel() {
  const t = useT('usage')
  const tc = useT('common')
  const formId = useId()
  const now = new Date()
  const form = useZodForm(schema, {
    customerId: '',
    metric: '',
    from: formatUtcInput(new Date(now.getTime() - 30 * 24 * 3600_000)),
    to: formatUtcInput(now),
  })
  const run = useAggregates()
  const totals = run.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <Card title={t('aggregates')} description={t('aggregateHint')}>
        <form
          id={formId}
          noValidate
          className="grid gap-3 md:grid-cols-2"
          onSubmit={(event) => void form.handleSubmit((values) => run.mutate(values))(event)}
        >
          <FormEntityPicker
            control={form.control}
            name="customerId"
            label={tc('picker.customer')}
            useOptions={useCustomerOptions}
          />
          <FormField control={form.control} name="metric" label={t('metric')} mono optional />
          <FormField control={form.control} name="from" label={t('from')} type="datetime-utc" />
          <FormField control={form.control} name="to" label={t('to')} type="datetime-utc" />
        </form>
        <div className="mt-4 flex justify-end">
          <Button type="submit" form={formId} loading={run.isPending}>
            {t('run')}
          </Button>
        </div>
      </Card>
      {run.error ? (
        <ErrorPanel error={run.error} />
      ) : run.isSuccess && totals.length === 0 ? (
        <EmptyState icon="layout-dashboard" title={t('noTotals')} />
      ) : totals.length > 0 ? (
        <Totals totals={totals} />
      ) : null}
    </div>
  )
}

function Totals({ totals }: { totals: TotalResponse[] }) {
  const t = useT('usage')
  const data = totals.map((total) => ({
    metric: total.metric ?? '',
    quantity: Number(total.quantity ?? 0),
    events: total.events ?? 0,
    unit: total.unit ?? '',
  }))
  return (
    <Card title={t('chart')}>
      <ChartFrame height={240}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
            <XAxis dataKey="metric" fontSize={12} />
            <YAxis fontSize={12} />
            <ChartTooltip />
            <Bar dataKey="quantity" fill="var(--chart-1)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </ChartFrame>
      <div className="mt-4">
        <DataTable
          columns={[
            { key: 'metric', header: t('metric'), mono: true, mobile: 'primary' },
            { key: 'quantity', header: t('quantity'), align: 'end', mobile: 'secondary' },
            { key: 'unit', header: t('unit'), mobile: 'secondary' },
            { key: 'events', header: t('events'), align: 'end', mobile: 'meta' },
          ]}
          rows={data}
          rowKey={(row) => row.metric}
          caption={t('table')}
        />
      </div>
    </Card>
  )
}
