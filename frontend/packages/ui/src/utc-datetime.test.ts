import { describe, expect, it } from 'vitest'
import { formatUtcInput, parseUtcInput } from './utc-datetime'

describe('UTC date-time input', () => {
  it('formats an instant as UTC text', () => {
    expect(formatUtcInput(new Date('2026-11-20T09:05:00Z'))).toBe('2026-11-20 09:05')
  })

  it('parses UTC text to an ISO instant', () => {
    expect(parseUtcInput('2026-11-20 09:05')).toBe('2026-11-20T09:05:00.000Z')
    expect(parseUtcInput('  2026-11-20 09:05  ')).toBe('2026-11-20T09:05:00.000Z')
  })

  it('rejects text that is not a date-time', () => {
    expect(parseUtcInput('20/11/2026')).toBeNull()
    expect(parseUtcInput('2026-13-40 10:00')).toBeNull()
  })

  it('round-trips', () => {
    const iso = '2026-02-28T23:59:00.000Z'
    expect(parseUtcInput(formatUtcInput(new Date(iso)))).toBe(iso)
  })
})
