import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Tabs } from './tabs'

const items = [
  { id: 'all', label: 'All', count: 1200 },
  { id: 'paid', label: 'Paid', count: 40 },
]

describe('Tabs', () => {
  it('formats the count and marks the selected tab in the tiles variant', () => {
    render(<Tabs items={items} defaultValue="paid" variant="tiles" />)
    const paid = screen.getByRole('tab', { name: /Paid/ })
    expect(paid).toHaveAttribute('aria-selected', 'true')
    expect(paid).toHaveTextContent('40')
    expect(screen.getByText('1,200')).toBeInTheDocument()
  })

  it('keeps the plain count in the segmented variant', () => {
    render(<Tabs items={items} defaultValue="all" />)
    expect(screen.getByRole('tab', { name: /All/ })).toHaveTextContent('1200')
  })
})
