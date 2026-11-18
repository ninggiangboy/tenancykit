import type { CommandItem } from '@suite/shell-sdk'

export interface CommandRegistry {
  register(items: CommandItem[]): () => void
  items(): CommandItem[]
  subscribe(listener: () => void): () => void
}

/**
 * The palette's collection point. A remote registers its actions while it is mounted and takes them back on unmount, so
 * the palette never offers a screen that is no longer loaded.
 */
export function createCommandRegistry(): CommandRegistry {
  let items: CommandItem[] = []
  const listeners = new Set<() => void>()
  const notify = () => {
    for (const listener of listeners) {
      listener()
    }
  }

  return {
    register(next) {
      const ids = new Set(next.map((item) => item.id))
      items = [...items.filter((item) => !ids.has(item.id)), ...next]
      notify()
      return () => {
        items = items.filter((item) => !ids.has(item.id))
        notify()
      }
    },
    items() {
      return items
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
