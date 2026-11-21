import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Switch } from './switch'

describe('Switch', () => {
  it('while saving it keeps its look, shows a spinner on the knob and refuses a second toggle', () => {
    render(<Switch aria-label="Billing" checked busy />)
    const control = screen.getByRole('switch', { name: 'Billing' })
    expect(control).toHaveAttribute('aria-busy', 'true')
    expect(control).toHaveAttribute('data-busy')
    expect(control).toBeDisabled()
    expect(control).toHaveClass('data-[busy]:disabled:opacity-100')
    expect(control.querySelector('.animate-spin')).not.toBeNull()
  })

  it('at rest it has no spinner and no busy mark', () => {
    render(<Switch aria-label="Billing" checked={false} />)
    const control = screen.getByRole('switch', { name: 'Billing' })
    expect(control).not.toHaveAttribute('data-busy')
    expect(control.querySelector('.animate-spin')).toBeNull()
  })
})
