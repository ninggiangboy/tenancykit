import { rules } from '@suite/remote-kit'
import { z } from 'zod'

export const invoiceChargeSchema = z.object({ chargeId: rules.id() })
