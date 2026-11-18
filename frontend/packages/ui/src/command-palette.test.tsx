import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CommandPalette } from './command-palette'

const groups = [
  {
    label: 'Remotes',
    items: [
      { id: 'billing', label: 'Billing', hint: 'remote', onSelect: vi.fn() },
      { id: 'platform', label: 'Platform', hint: 'remote', onSelect: vi.fn() },
    ],
  },
  {
    label: 'Records',
    items: [{ id: 'inv', label: 'INV-2026-00412', keywords: 'invoice acme', onSelect: vi.fn() }],
  },
]

describe('CommandPalette', () => {
  it('filters by the query and runs the chosen item', async () => {
    render(<CommandPalette open onClose={() => {}} groups={groups} />)

    const input = screen.getByPlaceholderText('Search or jump to')
    await userEvent.type(input, 'INV')
    expect(screen.getByText('INV-2026-00412')).toBeInTheDocument()
    expect(screen.queryByText('Billing')).not.toBeInTheDocument()

    await userEvent.click(screen.getByText('INV-2026-00412'))
    expect(groups[1]?.items[0]?.onSelect).toHaveBeenCalled()
  })

  it('matches on keywords, not only on the label', async () => {
    render(<CommandPalette open onClose={() => {}} groups={groups} />)

    await userEvent.type(screen.getByPlaceholderText('Search or jump to'), 'acme')

    expect(screen.getByText('INV-2026-00412')).toBeInTheDocument()
  })

  it('says there is no match instead of showing an empty box', async () => {
    render(<CommandPalette open onClose={() => {}} groups={groups} />)

    await userEvent.type(screen.getByPlaceholderText('Search or jump to'), 'zzzz')

    expect(screen.getByText('No match')).toBeInTheDocument()
  })
})
