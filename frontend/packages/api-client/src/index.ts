/**
 * Where each backend answers from the console's own origin, matching the Caddy routes: Platform under `/platform`,
 * Billing at the root because every unmatched path already goes there.
 */
export { type ApiName, BILLING_BASE_URL, baseUrlFor, PLATFORM_BASE_URL } from './base-url'

export { type ApiClientContext, type ApiClients, createApiClients } from './clients'
export { ApiError, etag, type ProblemDetail } from './errors'
export { permissions } from './generated/permissions'
export { type FilterOperator, type ListFilter, type ListQueryInput, listQuery, type SortTerm } from './list-query'
