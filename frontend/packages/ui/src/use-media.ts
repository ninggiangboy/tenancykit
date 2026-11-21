import { useSyncExternalStore } from 'react'

export const PHONE_QUERY = '(max-width: 767.98px)'
export const TABLET_QUERY = '(min-width: 768px) and (max-width: 1023.98px)'

interface Watched {
  /** The `matchMedia` this entry was made with; a different one (a test's stub) makes a new entry. */
  source: unknown
  list: MediaQueryList | undefined
  subscribe: (notify: () => void) => () => void
}

const watched = new Map<string, Watched>()

/** One list and one stable `subscribe` per query, so a re-render never unsubscribes and subscribes again. */
function watch(query: string): Watched {
  const source = globalThis.matchMedia
  const known = watched.get(query)
  if (known && known.source === source) return known
  const list = source?.call(globalThis, query)
  const entry: Watched = {
    source,
    list,
    subscribe: (notify) => {
      list?.addEventListener('change', notify)
      return () => list?.removeEventListener('change', notify)
    },
  }
  watched.set(query, entry)
  return entry
}

/** Whether a media query matches, kept current. Only the kit and the shell call it; a screen declares and the kit decides. */
export function useMedia(query: string): boolean {
  return useSyncExternalStore(
    watch(query).subscribe,
    () => watch(query).list?.matches ?? false,
    () => false,
  )
}
