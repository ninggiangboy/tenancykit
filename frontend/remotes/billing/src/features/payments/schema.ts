import { rules } from '@suite/remote-kit'
import { z } from 'zod'
import { amount } from '../../components/money'

export const refundSchema = z.object({ amount: amount(), reason: rules.optionalText() })

export const topUpSchema = z.object({ walletId: rules.id(), amount: amount(), currency: rules.currency() })
