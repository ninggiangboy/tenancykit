import { rules } from '@suite/remote-kit'
import { z } from 'zod'
import { PRICING_MODELS, requireModelFields, versionFields } from './rule'

export const createPlanSchema = z
  .object({
    code: rules.text(),
    name: rules.text(),
    currency: rules.currency(),
    model: z.enum(PRICING_MODELS),
    ...versionFields,
  })
  .superRefine((values, ctx) => requireModelFields(values.model, values, ctx))

/** A new version keeps the plan's model, which the caller checks against. */
export const publishVersionSchema = z
  .object({ model: z.enum(PRICING_MODELS), ...versionFields })
  .superRefine((values, ctx) => requireModelFields(values.model, values, ctx))
