import { rules } from '@suite/remote-kit'
import { z } from 'zod'
import { amount } from '../../components/money'

const optionalDate = () =>
  z
    .string()
    .optional()
    .transform((value) => (value ? new Date(value).toISOString() : undefined))

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
