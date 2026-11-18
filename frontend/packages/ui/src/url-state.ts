/**
 * The design system keeps its state in the URL: tabs, filters, sort, page size and the open record. This adapter is the
 * whole of its router knowledge, so a remote can hand it TanStack Router's search object and navigate function and a
 * test can hand it a plain object.
 */
export interface UrlState {
  get(key: string): string | null
  /** Sets or deletes keys in one navigation, leaving every other search parameter alone. */
  set(values: Record<string, string | null | undefined>): void
}

export type SearchRecord = Record<string, unknown>

export function urlState(search: SearchRecord, navigate: (next: SearchRecord) => void): UrlState {
  return {
    get(key) {
      const value = search[key]
      if (value === undefined || value === null) {
        return null
      }
      return Array.isArray(value) ? value.join(',') : String(value)
    },
    set(values) {
      const next: SearchRecord = { ...search }
      for (const [key, value] of Object.entries(values)) {
        if (value === null || value === undefined || value === '') {
          delete next[key]
        } else {
          next[key] = value
        }
      }
      navigate(next)
    },
  }
}

/** A comma-separated list parameter, as a faceted filter stores one field's values. */
export function listParam(value: string | null): string[] {
  return value ? value.split(',').filter(Boolean) : []
}

/** A whole number parameter that falls back rather than producing NaN. */
export function numberParam(value: string | null, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

/** Adds or removes one value of a multi-value parameter. */
export function toggleValue(values: string[], value: string): string[] {
  return values.includes(value) ? values.filter((entry) => entry !== value) : [...values, value]
}
