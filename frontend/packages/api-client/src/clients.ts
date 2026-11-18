import { type ApiName, baseUrlFor } from './base-url'
import { type Client as BillingClient, createClient as createBillingClient } from './generated/billing/client'
import { createClient as createPlatformClient, type Client as PlatformClient } from './generated/platform/client'

/**
 * What a client needs from the shell. Tokens stay in the shell's memory; a remote asks through this and never reads
 * storage.
 */
export interface ApiClientContext {
  getToken: () => Promise<string>
  /** The caller's trace id; one is generated when the caller does not supply a source. */
  requestId?: () => string
  /**
   * Where each backend lives. The console calls its own origin with relative paths; a test or an external tool can
   * answer with an absolute base instead.
   */
  resolveBaseUrl?: (api: ApiName) => string
}

export interface ApiClients {
  platform: PlatformClient
  billing: BillingClient
}

/**
 * One client per backend, both on the console's own origin: Platform under `/platform`, Billing at the root, exactly
 * the Caddy routes. Every request carries the token; every mutation carries an idempotency key the server ignores where
 * it declares none, and a trace id.
 */
export function createApiClients(context: ApiClientContext): ApiClients {
  const baseUrl = context.resolveBaseUrl ?? baseUrlFor
  return {
    platform: intercept(createPlatformClient({ baseUrl: baseUrl('platform') }), context),
    billing: intercept(createBillingClient({ baseUrl: baseUrl('billing') }), context),
  }
}

/** The generated clients share this shape; one interceptor set covers both. */
function intercept<C extends PlatformClient | BillingClient>(client: C, context: ApiClientContext): C {
  client.interceptors.request.use(async (request) => {
    request.headers.set('Authorization', `Bearer ${await context.getToken()}`)
    if (!request.headers.has('X-Request-Id')) {
      request.headers.set('X-Request-Id', context.requestId?.() ?? randomId())
    }
    const method = (request.method ?? 'GET').toUpperCase()
    if (method !== 'GET' && method !== 'HEAD' && !request.headers.has('Idempotency-Key')) {
      request.headers.set('Idempotency-Key', randomId())
    }
    return request
  })
  return client
}

function randomId(): string {
  return globalThis.crypto.randomUUID()
}
