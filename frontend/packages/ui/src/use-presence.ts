import { useEffect, useState } from 'react'
import { LEAVE_MS } from './motion'

/** True when the person asked for no motion; every duration is zero then, so nothing needs to stay mounted to play. */
function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Keeps the last value of something that comes and goes, such as a selected record or a bar's count, for `exitMs`
 * after it goes, so the element it draws can play its leave animation instead of vanishing in the same commit. `shown`
 * is the value while it is set, then the last one until the time is up, then `null`; a new value replaces it at once.
 */
export function usePresence<T>(
  value: T | null | undefined,
  exitMs: number = LEAVE_MS,
): { shown: T | null; present: boolean } {
  const [held, setHeld] = useState<T | null>(value ?? null)
  const instant = prefersReducedMotion()

  useEffect(() => {
    if (value != null) {
      setHeld(value)
      return
    }
    const timer = setTimeout(() => setHeld(null), instant ? 0 : exitMs)
    return () => clearTimeout(timer)
  }, [value, exitMs, instant])

  const shown = value ?? (instant ? null : held)
  return { shown, present: shown !== null }
}
