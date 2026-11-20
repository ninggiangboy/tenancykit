const UTC_INPUT = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/

/** A date as the `YYYY-MM-DD HH:mm` text of a UTC date-time field, whatever the viewer's timezone is. */
export function formatUtcInput(date: Date): string {
  return date.toISOString().slice(0, 16).replace('T', ' ')
}

/** The ISO 8601 instant for `YYYY-MM-DD HH:mm` read as UTC, or `null` when the text is not a real date-time. */
export function parseUtcInput(value: string): string | null {
  const text = value.trim()
  if (!UTC_INPUT.test(text)) return null
  const date = new Date(`${text.replace(' ', 'T')}:00Z`)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
