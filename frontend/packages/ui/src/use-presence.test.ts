import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePresence } from './use-presence'

function reduceMotion(reduce: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: reduce && query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
}

describe('usePresence', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    reduceMotion(false)
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('holds the last value for the exit time and then lets go', () => {
    const { result, rerender } = renderHook(({ value }) => usePresence(value, 180), {
      initialProps: { value: 'a' as string | null },
    })
    expect(result.current).toEqual({ shown: 'a', present: true })

    rerender({ value: null })
    expect(result.current.shown).toBe('a')
    act(() => vi.advanceTimersByTime(179))
    expect(result.current.shown).toBe('a')
    act(() => vi.advanceTimersByTime(2))
    expect(result.current).toEqual({ shown: null, present: false })
  })

  it('shows a new value at once and cancels the wait for the old one', () => {
    const { result, rerender } = renderHook(({ value }) => usePresence(value, 180), {
      initialProps: { value: 'a' as string | null },
    })
    rerender({ value: null })
    act(() => vi.advanceTimersByTime(100))
    rerender({ value: 'b' })
    expect(result.current.shown).toBe('b')
    act(() => vi.advanceTimersByTime(500))
    expect(result.current.shown).toBe('b')
  })

  it('starts empty and appears when a value arrives', () => {
    const { result, rerender } = renderHook(({ value }) => usePresence(value), {
      initialProps: { value: null as number | null },
    })
    expect(result.current).toEqual({ shown: null, present: false })
    rerender({ value: 3 })
    expect(result.current).toEqual({ shown: 3, present: true })
  })

  it('clears its timer when the component unmounts', () => {
    const { rerender, unmount } = renderHook(({ value }) => usePresence(value, 180), {
      initialProps: { value: 'a' as string | null },
    })
    rerender({ value: null })
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('lets go at once when motion is reduced', () => {
    reduceMotion(true)
    const { result, rerender } = renderHook(({ value }) => usePresence(value, 180), {
      initialProps: { value: 'a' as string | null },
    })
    rerender({ value: null })
    expect(result.current.shown).toBeNull()
  })
})
