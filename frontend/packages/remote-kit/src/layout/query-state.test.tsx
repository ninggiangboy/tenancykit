import { fireEvent, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderKit } from '../test-utils'
import { QueryState, type QueryStateSource } from './query-state'

const base: QueryStateSource<string[]> = {
  isLoading: false,
  isError: false,
  error: null,
  data: undefined,
  refetch: () => undefined,
}

function show(query: Partial<QueryStateSource<string[]>>) {
  return renderKit(
    <QueryState query={{ ...base, ...query }} empty={<p>Nothing here</p>}>
      {(items) => (
        <ul>
          {items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      )}
    </QueryState>,
  )
}

describe('QueryState', () => {
  it('shows a placeholder while loading', () => {
    const { container } = show({ isLoading: true })
    expect(container.querySelector('.animate-shimmer')).not.toBeNull()
    expect(screen.queryByText('Nothing here')).toBeNull()
  })

  it('shows the server sentence and a Retry that refetches when the read failed, never the empty state', () => {
    const refetch = vi.fn()
    show({ isError: true, error: { detail: 'Database is down' }, refetch })
    expect(screen.getByText('Could not load this section.')).toBeInTheDocument()
    expect(screen.getByText('Database is down')).toBeInTheDocument()
    expect(screen.queryByText('Nothing here')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('shows the empty state for an empty answer and the data otherwise', () => {
    const { unmount } = show({ data: [] })
    expect(screen.getByText('Nothing here')).toBeInTheDocument()
    unmount()
    show({ data: ['one', 'two'] })
    expect(screen.getByText('two')).toBeInTheDocument()
  })

  it('keeps the last data under the alert when a refetch fails', () => {
    show({ data: ['one'], isError: true, error: { detail: 'Gateway timeout' } })
    expect(screen.getByText('Gateway timeout')).toBeInTheDocument()
    expect(screen.getByText('one')).toBeInTheDocument()
  })
})
