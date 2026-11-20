import type { PlanResponse } from '@suite/api-client/billing'
import { ConfirmDialog, FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { useEffect } from 'react'
import { useEnumLabel } from '../../../components/facets'
import { asOptions, CURRENCIES } from '../../../components/options'
import { useT } from '../../../i18n'
import { useCreatePlan, usePublishVersion, useRetirePlan } from '../hooks/use-plans'
import { fromRule, PRICING_MODELS, type PricingModel, toVersion } from '../rule'
import { createPlanSchema, publishVersionSchema } from '../schema'
import { RuleFields } from './rule-fields'

const emptyRule = { metric: '', unit: '', unitPrice: '', increment: '', executionFee: '', fixedPrice: '' }

export function CreatePlanDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('plans')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const form = useZodForm(createPlanSchema, {
    code: '',
    name: '',
    currency: 'USD',
    model: 'SUBSCRIPTION',
    billingCycle: 'MONTHLY',
    ...emptyRule,
  })
  const model = form.watch('model') as PricingModel
  const currency = form.watch('currency') ?? 'USD'
  const create = useCreatePlan(() => {
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
      size="lg"
      onSubmit={(values) =>
        create.mutate({
          code: values.code,
          name: values.name,
          currency: values.currency,
          model: values.model,
          version: toVersion(values.model, values),
        })
      }
    >
      <FormField control={form.control} name="code" label={t('code')} mono autoFocus />
      <FormField control={form.control} name="name" label={tc('fields.name')} />
      <FormField
        control={form.control}
        name="currency"
        label={tc('fields.currency')}
        type="select"
        options={asOptions(CURRENCIES)}
      />
      <FormField
        control={form.control}
        name="model"
        label={t('model')}
        hint={t('modelHint')}
        type="select"
        options={asOptions(PRICING_MODELS, enumLabel)}
      />
      <RuleFields control={form.control} model={model} currency={currency} />
    </FormDialog>
  )
}

interface Props {
  plan: PlanResponse
  open: boolean
  onClose: () => void
}

/** A new immutable version, starting from the latest one's values. */
export function PublishVersionDialog({ plan, open, onClose }: Props) {
  const t = useT('plans')
  const model = (plan.model ?? 'SUBSCRIPTION') as PricingModel
  const latest = [...(plan.versions ?? [])].sort((left, right) => (right.version ?? 0) - (left.version ?? 0))[0]
  const defaults = { model, billingCycle: latest?.billingCycle ?? 'MONTHLY', ...emptyRule, ...fromRule(latest?.rule) }
  const form = useZodForm(publishVersionSchema, defaults)
  const { reset } = form
  useEffect(() => {
    if (open) reset(defaults)
  }, [open, plan.id, latest?.version, reset])
  const publish = usePublishVersion(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('publish')}
      description={t('createHint')}
      submitLabel={t('publish')}
      busy={publish.isPending}
      form={form}
      size="lg"
      onSubmit={(values) => publish.mutate({ id: plan.id ?? '', body: toVersion(model, values) })}
    >
      <RuleFields control={form.control} model={model} currency={plan.currency ?? 'USD'} />
    </FormDialog>
  )
}

export function RetirePlanDialog({ plan, open, onClose }: Props) {
  const t = useT('plans')
  const tc = useT('common')
  const retire = useRetirePlan(onClose)
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      title={tc('confirm.retirePlan')}
      description={tc('confirm.retirePlanHint')}
      confirmLabel={t('retire')}
      busy={retire.isPending}
      onConfirm={() => retire.mutate(plan.id ?? '')}
      impact={[
        { label: tc('fields.plan'), value: plan.name ?? '' },
        { label: t('versions'), value: plan.versions?.length ?? 0 },
      ]}
    />
  )
}
