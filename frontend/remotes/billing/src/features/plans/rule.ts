import type { RuleDto, VersionRequest } from '@suite/api-client/billing'
import { rules } from '@suite/remote-kit'
import { z } from 'zod'

export const PRICING_MODELS = [
  'SUBSCRIPTION',
  'USAGE_BASED',
  'EVENT_BASED',
  'TIME_BASED',
  'WORKLOAD_BASED',
  'HYBRID',
] as const
export type PricingModel = (typeof PRICING_MODELS)[number]

/** Which rule fields a model reads, as the Billing API documents them on `RuleDto`. */
export const MODEL_FIELDS: Record<
  PricingModel,
  Array<'metric' | 'unit' | 'unitPrice' | 'increment' | 'executionFee' | 'fixedPrice' | 'component'>
> = {
  SUBSCRIPTION: ['fixedPrice'],
  USAGE_BASED: ['metric', 'unit', 'unitPrice'],
  EVENT_BASED: ['metric', 'unit', 'unitPrice'],
  TIME_BASED: ['metric', 'unit', 'unitPrice', 'increment'],
  WORKLOAD_BASED: ['metric', 'unit', 'unitPrice', 'executionFee'],
  HYBRID: ['fixedPrice', 'metric', 'unit', 'unitPrice'],
}

const decimal = z
  .string()
  .trim()
  .optional()
  .transform((value) => (value ? value : undefined))
  .pipe(
    z
      .string()
      .regex(/^\d+(\.\d+)?$/, { message: 'form.invalid' })
      .optional(),
  )

/** The fields of a version: its cycle and the rule, each required only when the model reads it. */
export const versionFields = {
  billingCycle: rules.text(),
  metric: rules.optionalText(),
  unit: rules.optionalText(),
  unitPrice: decimal,
  increment: decimal,
  executionFee: decimal,
  fixedPrice: decimal,
}

type VersionValues = {
  billingCycle: string
  metric?: string
  unit?: string
  unitPrice?: string
  increment?: string
  executionFee?: string
  fixedPrice?: string
}

const OPTIONAL = new Set(['increment', 'executionFee'])

/** Adds a "required" issue to every field the model reads and the person left empty. */
export function requireModelFields(model: PricingModel, values: VersionValues, ctx: z.RefinementCtx) {
  for (const field of MODEL_FIELDS[model]) {
    if (field === 'component' || OPTIONAL.has(field)) continue
    if (!values[field as keyof VersionValues]) ctx.addIssue({ code: 'custom', path: [field], message: 'form.required' })
  }
}

/** The request body of a version for a model; a hybrid plan carries its usage part as one usage-based component. */
export function toVersion(model: PricingModel, values: VersionValues): VersionRequest {
  const usage: RuleDto = { metric: values.metric, unit: values.unit, unitPrice: values.unitPrice }
  const rule: RuleDto =
    model === 'SUBSCRIPTION'
      ? { model, fixedPrice: values.fixedPrice }
      : model === 'HYBRID'
        ? { model, fixedPrice: values.fixedPrice, components: [{ model: 'USAGE_BASED', ...usage }] }
        : model === 'TIME_BASED'
          ? { model, ...usage, increment: values.increment }
          : model === 'WORKLOAD_BASED'
            ? { model, ...usage, executionFee: values.executionFee }
            : { model, ...usage }
  return { billingCycle: values.billingCycle, rule }
}

/** A version's rule back into form values, so a new version starts from the last one. */
export function fromRule(rule: RuleDto | undefined): Omit<VersionValues, 'billingCycle'> {
  const usage = rule?.components?.[0] ?? rule
  return {
    metric: usage?.metric ?? '',
    unit: usage?.unit ?? '',
    unitPrice: usage?.unitPrice ?? usage?.tiers?.[0]?.unitPrice ?? '',
    increment: rule?.increment ?? '',
    executionFee: rule?.executionFee ?? '',
    fixedPrice: rule?.fixedPrice ?? '',
  }
}
