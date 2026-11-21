import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FilterChip } from './filter-chip'
import { UiMessagesProvider } from './messages'
import { Tabs } from './tabs'

describe('words that were once English only', () => {
  it('the filter chip speaks the console language, in its operator and its remove button', () => {
    render(
      <UiMessagesProvider locale="vi">
        <FilterChip field="status" label="Trạng thái" op="neq" value="ACTIVE" onRemove={() => {}} />
      </UiMessagesProvider>,
    )
    expect(screen.getByText('không phải')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Bỏ bộ lọc Trạng thái' })).toBeInTheDocument()
    expect(screen.queryByText('is not')).toBeNull()
  })

  it('the filter chip keeps English in English', () => {
    render(<FilterChip field="status" op="like" value="a" onRemove={() => {}} />)
    expect(screen.getByText('matches')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove the status filter' })).toBeInTheDocument()
  })

  it('the tile count formats with the console language', () => {
    render(
      <UiMessagesProvider locale="vi">
        <Tabs variant="tiles" items={[{ id: 'all', label: 'Tất cả', count: 12345 }]} />
      </UiMessagesProvider>,
    )
    expect(screen.getByText('12.345')).toBeInTheDocument()
  })
})
