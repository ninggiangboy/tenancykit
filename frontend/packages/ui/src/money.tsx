import { cn } from './cn'

/** Currencies whose smallest unit is the unit itself. Everything else in the suite uses two decimals. */
const ZERO_DECIMAL = new Set(['VND', 'JPY', 'KRW', 'IDR', 'CLP', 'ISK'])

export interface MoneyProps {
  /** The amount in minor units, as the API stores it. */
  minor: number
  currency?: string
  locale?: string
  signed?: boolean
  className?: string
}

/**
 * An amount in minor units, with the currency's own decimals and its code, in one place: `12,500.00 USD`,
 * `3,200,000 VND`. Never a float, never a symbol without its code.
 */
export function Money({ minor, currency = 'USD', locale = 'en', signed, className }: MoneyProps) {
  const decimals = ZERO_DECIMAL.has(currency.toUpperCase()) ? 0 : 2
  const amount = decimals === 0 ? minor : minor / 100
  const formatted = new Intl.NumberFormat(locale, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(Math.abs(amount))
  const sign = amount < 0 ? '−' : signed && amount > 0 ? '+' : ''
  return (
    <span className={cn('tabular-nums', className)} data-currency={currency}>
      {sign}
      {formatted} {currency.toUpperCase()}
    </span>
  )
}
