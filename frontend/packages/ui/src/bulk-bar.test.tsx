import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BulkBar } from './bulk-bar'
import { UnsavedBar } from './unsaved-bar'

describe('floating bars', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('the bulk bar leaves with its leave animation and keeps the last count meanwhile', () => {
    const { rerender } = render(<BulkBar count={3} onClear={() => {}} />)
    const bar = screen.getByRole('region', { name: '3 selected' })
    expect(bar).toHaveAttribute('data-state', 'open')

    rerender(<BulkBar count={0} onClear={() => {}} />)
    const leaving = screen.getByRole('region', { name: '3 selected' })
    expect(leaving).toHaveAttribute('data-state', 'closed')
    expect(leaving).toHaveClass('data-[state=closed]:animate-fade-out')

    act(() => vi.advanceTimersByTime(200))
    expect(screen.queryByRole('region')).toBeNull()
  })

  it('the unsaved bar leaves the same way', () => {
    const { container, rerender } = render(<UnsavedBar count={2} onDiscard={() => {}} onSave={() => {}} />)
    expect(container.querySelector('section')).toHaveAttribute('data-state', 'open')

    rerender(<UnsavedBar count={0} onDiscard={() => {}} onSave={() => {}} />)
    expect(container.querySelector('section')).toHaveAttribute('data-state', 'closed')

    act(() => vi.advanceTimersByTime(200))
    expect(container.querySelector('section')).toBeNull()
  })
})
