/** Values the Billing API accepts for its enums; a form offers these instead of a free-text box. */
export const WALLET_MODES = ['PREPAID', 'POSTPAID'] as const
export const BILLING_CYCLES = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'] as const
export const CURRENCIES = ['USD', 'EUR', 'GBP', 'VND', 'JPY', 'SGD'] as const

export function asOptions(values: readonly string[], label: (value: string) => string = (value) => value) {
  return values.map((value) => ({ value, label: label(value) }))
}
