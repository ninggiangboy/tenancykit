import { rules } from '@suite/remote-kit'
import { z } from 'zod'

export const createRoleSchema = z.object({ name: rules.text(), permissions: z.array(z.string()) })

export const renameRoleSchema = z.object({ name: rules.text() })
