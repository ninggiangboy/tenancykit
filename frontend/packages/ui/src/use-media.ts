import { useSyncExternalStore } from 'react'

export const PHONE_QUERY = '(max-width: 767.98px)'
export const TABLET_QUERY = '(min-width: 768px) and (max-width: 1023.98px)'

/** Whether a media query matches, kept current. Only the kit and the shell call it; a screen declares and the kit decides. */
export function useMedia(query: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      const list = globalThis.matchMedia?.(query)
      list?.addEventListener('change', notify)
      return () => list?.removeEventListener('change', notify)
    },
    () => globalThis.matchMedia?.(query).matches ?? false,
    () => false,
  )
}
