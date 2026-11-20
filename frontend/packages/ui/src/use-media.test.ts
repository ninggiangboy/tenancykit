import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PHONE_QUERY, useMedia } from './use-media'

function stubMatchMedia(initial: boolean) {
  const listeners = new Set<() => void>()
  const state = { matches: initial }
  vi.stubGlobal('matchMedia', () => ({
    get matches() {
      return state.matches
    },
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }))
  return {
    set(matches: boolean) {
      state.matches = matches
      for (const listener of listeners) listener()
    },
    listeners,
  }
}

describe('useMedia', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('returns the current match and follows a change event', () => {
    const media = stubMatchMedia(false)
    const { result } = renderHook(() => useMedia(PHONE_QUERY))
    expect(result.current).toBe(false)
    act(() => media.set(true))
    expect(result.current).toBe(true)
  })

  it('stops listening when the component unmounts', () => {
    const media = stubMatchMedia(true)
    const { unmount } = renderHook(() => useMedia(PHONE_QUERY))
    expect(media.listeners.size).toBe(1)
    unmount()
    expect(media.listeners.size).toBe(0)
  })

  it('is false where matchMedia does not exist', () => {
    vi.stubGlobal('matchMedia', undefined)
    const { result } = renderHook(() => useMedia(PHONE_QUERY))
    expect(result.current).toBe(false)
  })
})
