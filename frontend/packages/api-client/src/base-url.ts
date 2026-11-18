/**
 * Where each backend answers from the console's own origin, matching the Caddy routes: Platform under `/platform`,
 * Billing at the root because every unmatched path already goes there.
 */
export const PLATFORM_BASE_URL = '/platform'
export const BILLING_BASE_URL = ''

export type ApiName = 'platform' | 'billing'

export function baseUrlFor(api: ApiName): string {
  return api === 'platform' ? PLATFORM_BASE_URL : BILLING_BASE_URL
}
