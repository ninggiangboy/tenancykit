import type { CreateCouponRequest } from '@suite/api-client/billing'
import { rules } from '@suite/remote-kit'
import { parseUtcInput } from '@suite/ui'
import { z } from 'zod'
import { toMinor } from '../../components/money'

export const BENEFITS = ['PERCENT_OFF', 'AMOUNT_OFF', 'WALLET_CREDIT'] as const

const optionalDate = () =>
  rules
    .optionalText()
    .refine((value) => !value || parseUtcInput(value) !== null, { message: 'errors.utcFormat' })
    .transform((value) => (value ? (parseUtcInput(value) as string) : undefined))

export const createCouponSchema = z
  .object({
    name: rules.text(),
    currency: rules.currency(),
    benefitType: z.enum(BENEFITS),
    value: z
      .string()
      .trim()
      .regex(/^\d+(\.\d+)?$/, { message: 'form.positive' }),
    code: rules.optionalText(),
    codeMaxRedemptions: rules.optionalCount(),
    maxRedemptions: rules.optionalCount(),
    maxPerCustomer: rules.optionalCount(),
    redeemBy: optionalDate(),
    stackable: z.boolean(),
  })
  .superRefine((values, ctx) => {
    const value = Number(values.value)
    if (values.benefitType === 'PERCENT_OFF' && (value <= 0 || value > 100)) {
      ctx.addIssue({ code: 'custom', path: ['value'], message: 'form.invalid' })
    }
  })

type Values = z.output<typeof createCouponSchema>

/** A percent becomes basis points (10% is 1000) and an amount becomes minor units, as the API takes them. */
export function toCouponRequest(values: Values): CreateCouponRequest {
  const percent = values.benefitType === 'PERCENT_OFF'
  return {
    name: values.name,
    currency: values.currency,
    benefit: percent
      ? { type: values.benefitType, basisPoints: Math.round(Number(values.value) * 100) }
      : { type: values.benefitType, amount: toMinor(values.value, values.currency) },
    limits:
      values.maxRedemptions || values.maxPerCustomer || values.redeemBy
        ? { maxRedemptions: values.maxRedemptions, maxPerCustomer: values.maxPerCustomer, redeemBy: values.redeemBy }
        : undefined,
    stackable: values.stackable,
    code: values.code,
    codeMaxRedemptions: values.codeMaxRedemptions,
  }
}

export const addCodeSchema = z.object({ code: rules.text(), maxRedemptions: rules.optionalCount() })
