import { rules } from '@suite/remote-kit'
import { z } from 'zod'

export const createTenantSchema = z.object({
  code: rules.text().pipe(z.string().regex(/^[a-z0-9][a-z0-9-]*$/, { message: 'form.invalid' })),
  name: rules.text(),
  adminEmail: rules.email(),
  adminTemporaryPassword: z.string().min(8, { message: 'form.invalid' }),
})

export const renameTenantSchema = z.object({ name: rules.text() })

export const addDomainSchema = z.object({
  hostname: rules.text().pipe(z.string().regex(/^[a-z0-9.-]+$/i, { message: 'form.invalid' })),
})
