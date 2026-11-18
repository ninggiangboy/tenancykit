/** Key names: `mod` is Cmd on macOS and Ctrl elsewhere; the rest are literal keys. */
export type KeyName = string

export function isMac(): boolean {
  if (typeof navigator === 'undefined') {
    return false
  }
  return /mac|iphone|ipad|ipod/i.test(navigator.platform || navigator.userAgent)
}

/** The label one key shows: `mod` is ⌘ or Ctrl, and the arrows are their glyphs. */
export function keyLabel(key: KeyName): string {
  switch (key) {
    case 'mod':
      return isMac() ? '⌘' : 'Ctrl'
    case 'shift':
      return '⇧'
    case 'alt':
      return isMac() ? '⌥' : 'Alt'
    case 'enter':
      return '↵'
    case 'esc':
      return 'Esc'
    case 'up':
      return '↑'
    case 'down':
      return '↓'
    default:
      return key.length === 1 ? key.toUpperCase() : key
  }
}

/** A combo such as `mod+k` or `/`, ready to compare against a keyboard event. */
export function matchesHotkey(combo: string, event: KeyboardEvent): boolean {
  const parts = combo
    .toLowerCase()
    .split('+')
    .map((part) => part.trim())
  const key = parts.pop() ?? ''
  const needsMod = parts.includes('mod')
  const needsShift = parts.includes('shift')
  const needsAlt = parts.includes('alt')
  const modPressed = isMac() ? event.metaKey : event.ctrlKey
  if (needsMod !== modPressed || needsShift !== event.shiftKey || needsAlt !== event.altKey) {
    return false
  }
  const pressed = event.key.toLowerCase()
  if (key === 'esc') return pressed === 'escape'
  if (key === 'enter') return pressed === 'enter'
  if (key === 'up') return pressed === 'arrowup'
  if (key === 'down') return pressed === 'arrowdown'
  return pressed === key
}
