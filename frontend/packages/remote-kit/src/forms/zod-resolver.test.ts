import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import * as rules from './schema'
import { zodResolver } from './zod-resolver'

const resolve = <S extends z.ZodType<Record<string, unknown>, Record<string, unknown>>>(schema: S, values: unknown) =>
  zodResolver(schema)(values as never, undefined, { fields: {}, shouldUseNativeValidation: false })

describe('zodResolver', () => {
  it('hands the parsed output to the submit', async () => {
    const schema = z.object({ amount: rules.minorUnits(), currency: rules.currency(), note: rules.optionalText() })
    const result = await resolve(schema, { amount: '1200', currency: 'usd', note: ' ' })
    expect(result.errors).toEqual({})
    expect(result.values).toEqual({ amount: 1200, currency: 'USD', note: undefined })
  })

  it('puts each issue on its field with a kit key as the message', async () => {
    const schema = z.object({ amount: rules.minorUnits(), walletId: rules.id(), name: rules.text() })
    const result = await resolve(schema, { amount: '0', walletId: 'not-an-id', name: '' })
    expect(result.errors).toMatchObject({
      amount: { message: 'form.positive' },
      walletId: { message: 'form.uuid' },
      name: { message: 'form.required' },
    })
  })

  it('nests errors of nested values', async () => {
    const schema = z.object({ limits: z.object({ count: rules.minorUnits() }) })
    const result = await resolve(schema, { limits: { count: 'x' } })
    expect(result.errors).toMatchObject({ limits: { count: { message: 'form.positive' } } })
  })
})

describe('rules', () => {
  it('splits lines and drops blanks', () => {
    expect(rules.lines().parse('a\n\n b \n')).toEqual(['a', 'b'])
  })

  it('treats an empty optional count as absent', () => {
    expect(rules.optionalCount().parse('')).toBeUndefined()
    expect(rules.optionalCount().parse('5')).toBe(5)
  })
})
