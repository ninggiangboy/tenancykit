import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Button } from './button'

describe('Button while loading', () => {
  it('without an icon, keeps the label in place under the spinner so the width does not change', () => {
    render(<Button loading>Save</Button>)
    const button = screen.getByRole('button')
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).toHaveClass('relative', 'disabled:opacity-100')
    const label = screen.getByText('Save')
    expect(label.closest('.invisible')).not.toBeNull()
    expect(button.querySelectorAll('.animate-spin')).toHaveLength(1)
  })

  it('with an icon, swaps the icon for the spinner', () => {
    render(
      <Button loading icon="plus">
        Add
      </Button>,
    )
    const button = screen.getByRole('button')
    expect(button.querySelectorAll('.animate-spin')).toHaveLength(1)
    expect(screen.getByText('Add').closest('.invisible')).toBeNull()
  })

  it('at rest renders just its label', () => {
    render(<Button>Save</Button>)
    expect(screen.getByRole('button').querySelector('.animate-spin')).toBeNull()
  })
})
