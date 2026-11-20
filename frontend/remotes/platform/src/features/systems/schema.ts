import { rules } from '@suite/remote-kit'
import { z } from 'zod'

export const createSystemSchema = z.object({ name: rules.text(), purpose: rules.text() })

export const renameSystemSchema = z.object({ name: rules.text() })

export const issueCredentialSchema = z.object({ label: rules.text(), scopes: z.array(z.string()) })

export const rotateSchema = z.object({ overlapMinutes: rules.optionalCount() })
