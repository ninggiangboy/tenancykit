import { rules } from '@suite/remote-kit'
import { z } from 'zod'

export const createUserSchema = z.object({
  tenantCode: rules.optionalText(),
  displayName: rules.text(),
  email: rules.email(),
  temporaryPassword: z.string().min(8, { message: 'form.invalid' }),
})

export const renameUserSchema = z.object({ displayName: rules.text() })
