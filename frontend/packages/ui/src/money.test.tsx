import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Money } from './money'

/** The amount and its code are two elements, so the text is read off the money element as a whole. */
const money = (text: string) =>
  screen.getByText((_, element) => element?.hasAttribute('data-currency') === true && element.textContent === text)

describe('Money', () => {
  it('renders minor units with the currency’s decimals and its code', () => {
    render(<Money minor={1250000} currency="USD" />)
    expect(money('12,500.00 USD')).toBeInTheDocument()
  })

  it('keeps a zero-decimal currency whole', () => {
    render(<Money minor={3200000} currency="VND" />)
    expect(money('3,200,000 VND')).toBeInTheDocument()
  })

  it('marks a negative amount', () => {
    render(<Money minor={-500} currency="USD" />)
    expect(money('−5.00 USD')).toBeInTheDocument()
  })

  it('adds a plus only when the caller asks for a signed delta', () => {
    render(<Money minor={500} currency="USD" signed />)
    expect(money('+5.00 USD')).toBeInTheDocument()
  })

  it('draws the currency code as the quieter part', () => {
    render(<Money minor={1250000} currency="USD" />)
    expect(screen.getByText('USD')).toHaveClass('text-muted-foreground')
  })
})
