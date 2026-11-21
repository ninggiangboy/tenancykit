import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSearchDraft } from './use-search-draft'

describe('useSearchDraft', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  const setup = (q = '') => {
    const setQ = vi.fn()
    const view = renderHook(({ q }) => useSearchDraft(q, setQ), { initialProps: { q } })
    return { setQ, ...view }
  }

  it('sends the last text once when five keys come inside 250ms', () => {
    const { result, setQ } = setup()
    for (const text of ['i', 'in', 'inv', 'invo', 'invoi']) {
      act(() => result.current.onChange(text))
      act(() => vi.advanceTimersByTime(40))
    }
    expect(setQ).not.toHaveBeenCalled()
    expect(result.current.draft).toBe('invoi')
    act(() => vi.advanceTimersByTime(250))
    expect(setQ).toHaveBeenCalledTimes(1)
    expect(setQ).toHaveBeenCalledWith('invoi')
  })

  it('sends at once on Enter and on leaving the box', () => {
    const { result, setQ } = setup()
    act(() => result.current.onChange('abc'))
    act(() => result.current.submit())
    expect(setQ).toHaveBeenCalledWith('abc')
    act(() => result.current.onChange('abcd'))
    act(() => result.current.onBlur())
    expect(setQ).toHaveBeenLastCalledWith('abcd')
    act(() => vi.advanceTimersByTime(500))
    expect(setQ).toHaveBeenCalledTimes(2)
  })

  it('Escape empties the box and the list at once', () => {
    const { result, setQ } = setup('old')
    act(() => result.current.clear())
    expect(result.current.draft).toBe('')
    expect(setQ).toHaveBeenCalledWith('')
  })

  it('takes a change from outside into the box', () => {
    const { result, rerender } = setup('abc')
    rerender({ q: '' })
    expect(result.current.draft).toBe('')
  })

  it('does not overwrite what the person is typing', () => {
    const { result, rerender } = setup('abc')
    act(() => result.current.onFocus())
    act(() => result.current.onChange('abcd'))
    rerender({ q: '' })
    expect(result.current.draft).toBe('abcd')
  })

  it('does not treat its own send as a change from outside', () => {
    const { result, rerender } = setup()
    act(() => result.current.onChange('abc'))
    act(() => vi.advanceTimersByTime(250))
    rerender({ q: 'abc' })
    expect(result.current.draft).toBe('abc')
  })
})
