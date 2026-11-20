import { rules } from '@suite/remote-kit'
import { parseUtcInput } from '@suite/ui'
import { z } from 'zod'
import { amount } from '../../components/money'

const optionalDate = () =>
  rules
    .optionalText()
    .refine((value) => !value || parseUtcInput(value) !== null, { message: 'errors.utcFormat' })
    .transform((value) => (value ? (parseUtcInput(value) as string) : undefined))

export const estimateSchema = z.object({
  planId: rules.id(),
  version: rules.optionalCount(),
  walletId: rules.optionalId(),
  at: optionalDate(),
  metric: rules.optionalText(),
  quantity: rules.optionalText(),
})

export const usageChargeSchema = z.object({
  customerId: rules.optionalId(),
  walletId: rules.optionalId(),
  planId: rules.optionalId(),
  version: rules.optionalCount(),
  from: optionalDate(),
  to: optionalDate(),
})

export const reserveSchema = z.object({
  walletId: rules.id(),
  amount: amount(),
  currency: rules.currency(),
  ttlSeconds: rules.optionalCount(),
  reference: rules.optionalText(),
})
