import { currencyDecimals } from '@suite/ui'
import { z } from 'zod'

/** The decimals `Money` shows for a currency, so what a person types matches what the screen shows. */
export function fractionDigits(currency: string | undefined): number {
  return currencyDecimals(currency || 'USD')
}

/** "10.50" USD becomes 1050; the API stores minor units. */
export function toMinor(amount: string | number, currency: string | undefined): number {
  return Math.round(Number(amount) * 10 ** fractionDigits(currency))
}

/** 1050 USD becomes "10.50", for a form that starts from a stored amount. */
export function fromMinor(minor: number | undefined, currency: string | undefined): string {
  const digits = fractionDigits(currency)
  return ((minor ?? 0) / 10 ** digits).toFixed(digits)
}

/** An amount as a person types it, in the currency's own units; above zero. */
export const amount = () =>
  z
    .string()
    .trim()
    .regex(/^\d+(\.\d+)?$/, { message: 'form.positive' })
    .refine((value) => Number(value) > 0, { message: 'form.positive' })
