import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Sheet } from './sheet'

const actions = <button type="button">Pay</button>

describe('Sheet', () => {
  it('becomes a bottom sheet with the actions in the dock', () => {
    render(
      <Sheet open onClose={() => {}} title="Invoice INV-1" actions={actions} placement="bottom">
        body
      </Sheet>,
    )
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveClass('rounded-t-[20px]')
    expect(screen.getAllByText('Pay')).toHaveLength(1)
    expect(dialog.querySelector('.border-t')).toContainElement(screen.getByText('Pay'))
  })

  it('keeps the actions in the header of a side sheet', () => {
    render(
      <Sheet open onClose={() => {}} title="Invoice INV-1" actions={actions} placement="side">
        body
      </Sheet>,
    )
    expect(screen.getByText('Pay').closest('header')).not.toBeNull()
  })
})
