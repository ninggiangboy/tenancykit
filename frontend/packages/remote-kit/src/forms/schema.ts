import { z } from 'zod'
import type { KitKey } from '../i18n/kit-catalog'

/**
 * Field rules the console's forms share. Each message is a kit key; `FormField` translates it, so a schema stays free
 * of a translator.
 */
const message = (key: KitKey) => ({ message: key })

/** A required line of text, trimmed. */
export const text = () => z.string().trim().min(1, message('form.required'))

/** An optional line of text; an empty box sends nothing. */
export const optionalText = () =>
  z
    .string()
    .trim()
    .optional()
    .transform((value) => (value ? value : undefined))

/** A record id: a picker fills it, a person may paste one. */
export const id = () => z.string().trim().uuid(message('form.uuid'))

export const optionalId = () =>
  z
    .string()
    .trim()
    .optional()
    .transform((value) => (value ? value : undefined))
    .pipe(z.string().uuid(message('form.uuid')).optional())

/** A whole amount in minor units (cents), above zero. */
export const minorUnits = () =>
  z.coerce.number(message('form.positive')).int(message('form.positive')).positive(message('form.positive'))

/** A whole number, zero or more; an empty box sends nothing. */
export const optionalCount = () =>
  z
    .union([z.literal(''), z.coerce.number().int(message('form.nonNegative')).nonnegative(message('form.nonNegative'))])
    .optional()
    .transform((value) => (value === '' || value === undefined ? undefined : value))

/** An ISO 4217 code, sent upper-case. */
export const currency = () =>
  z
    .string()
    .trim()
    .regex(/^[A-Za-z]{3}$/, message('form.currency'))
    .transform((value) => value.toUpperCase())

export const email = () => z.string().trim().email(message('form.email'))

export const url = () => z.string().trim().url(message('form.url'))

/** One value per line; blank lines are dropped. */
export const lines = () =>
  z
    .string()
    .optional()
    .transform((value) =>
      (value ?? '')
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    )
