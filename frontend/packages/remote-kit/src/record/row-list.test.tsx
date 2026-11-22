import { Button, StatusBadge } from '@suite/ui'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ListRow, RowList } from './row-list'

describe('RowList', () => {
  it('gives a row with a button and a row with a badge the same height and the same trailing column', () => {
    render(
      <RowList>
        <ListRow title="1.0.0" meta="SDK 1" trailing={<Button size="sm">Activate</Button>} />
        <ListRow title="1.1.0" meta="SDK 1" trailing={<StatusBadge status="ACTIVE">Active</StatusBadge>} selected />
      </RowList>,
    )
    const [button, badge] = screen.getAllByRole('listitem')
    for (const row of [button, badge]) {
      expect(row).toHaveClass('min-h-14', 'items-center')
      expect(row?.querySelector('.min-w-24')).not.toBeNull()
    }
    expect(badge).toHaveClass('bg-selection')
    expect(button).not.toHaveClass('bg-selection')
  })

  it('cuts the title, lets the meta wrap, and keeps the action in a column of its own', () => {
    render(
      <RowList>
        <ListRow
          title="A long reference"
          meta="a reference that is longer than twenty characters"
          action={<button type="button">Reverse</button>}
        />
      </RowList>,
    )
    expect(screen.getByText('A long reference')).toHaveClass('truncate')
    expect(screen.getByText(/longer than twenty/)).toHaveClass('break-words')
    expect(screen.getByRole('button', { name: 'Reverse' }).parentElement).toHaveClass('min-w-16')
  })
})
