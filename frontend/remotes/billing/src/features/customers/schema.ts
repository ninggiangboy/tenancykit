import { rules } from '@suite/remote-kit'
import { z } from 'zod'

const optionalEmail = () =>
  z
    .string()
    .trim()
    .optional()
    .transform((value) => (value ? value : undefined))
    .pipe(z.string().email({ message: 'form.email' }).optional())

export const createCustomerSchema = z.object({
  displayName: rules.text(),
  email: optionalEmail(),
  tenantSystemId: rules.optionalId(),
  externalUserId: rules.optionalText(),
})

export const editCustomerSchema = z.object({ displayName: rules.text(), email: optionalEmail() })
