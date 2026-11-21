import { useEffect, useRef, useState } from 'react'

/**
 * The text of a list's search box. It updates on every key and reaches the list (and so the URL and the request) 250ms
 * after the last one; Enter and leaving the box send it at once. A change that comes from outside (Clear, a chip, Back)
 * replaces the text, unless the person is typing in the box.
 */
export function useSearchDraft(q: string, setQ: (value: string) => void, delay = 250) {
  const [draft, setDraft] = useState(q)
  const sent = useRef(q)
  const focused = useRef(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const latest = useRef({ draft, setQ })
  latest.current = { draft, setQ }

  const flush = (value: string) => {
    clearTimeout(timer.current)
    timer.current = undefined
    if (value === sent.current) return
    sent.current = value
    latest.current.setQ(value)
  }

  useEffect(() => {
    if (q === sent.current) return
    const typing = focused.current && latest.current.draft !== sent.current
    if (typing) return
    sent.current = q
    setDraft(q)
  }, [q])

  useEffect(() => () => clearTimeout(timer.current), [])

  return {
    draft,
    onChange(value: string) {
      setDraft(value)
      clearTimeout(timer.current)
      timer.current = setTimeout(() => flush(value), delay)
    },
    /** Enter: the person is done typing. */
    submit: () => flush(latest.current.draft),
    /** Escape: start over. */
    clear() {
      setDraft('')
      flush('')
    },
    onFocus() {
      focused.current = true
    },
    onBlur() {
      focused.current = false
      flush(latest.current.draft)
    },
  }
}
