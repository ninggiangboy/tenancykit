import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { humanize, StatusBadge } from './status-badge'

describe('StatusBadge', () => {
  it('maps a suite enum to its tone and humanizes the word', () => {
    render(<StatusBadge status="PAST_DUE" />)
    const badge = screen.getByText('Past due').closest('[data-tone]')
    expect(badge).toHaveAttribute('data-tone', 'warning')
  })

  it('leaves a value it does not know neutral instead of hiding it', () => {
    render(<StatusBadge status="WAITING_FOR_SOMETHING" />)
    expect(screen.getByText('Waiting for something').closest('[data-tone]')).toHaveAttribute('data-tone', 'neutral')
  })

  it('takes an explicit tone and its own children', () => {
    render(
      <StatusBadge tone="danger" variant="plain">
        Expiring
      </StatusBadge>,
    )
    expect(screen.getByText('Expiring').closest('[data-tone]')).toHaveAttribute('data-tone', 'danger')
  })

  it('does not touch prose that is already a sentence', () => {
    expect(humanize('Some words')).toBe('Some words')
    expect(humanize('ACTIVE')).toBe('Active')
  })
})
