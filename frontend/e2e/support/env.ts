import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/** The repository root, from this file: `frontend/e2e/support`. */
export const repoRoot = resolve(import.meta.dirname, '../../..')
export const frontendDir = resolve(repoRoot, 'frontend')

/**
 * The compose file reads `.env`; the end-to-end tests do too, so a local run needs no exported variables. A variable
 * already in the environment wins, which is how CI passes what it generated.
 */
function loadDotEnv(path: string): void {
  if (!existsSync(path)) return
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const match = /^\s*([A-Z0-9_]+)=(.*)$/.exec(line)
    if (!match?.[1] || process.env[match[1]] !== undefined) continue
    process.env[match[1]] = (match[2] ?? '').replace(/^["']|["']$/g, '')
  }
}

loadDotEnv(resolve(repoRoot, '.env'))

const port = process.env.CONSOLE_PORT ?? '8090'
const baseDomain = process.env.CONSOLE_BASE_DOMAIN ?? 'localhost'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is not set; the console end-to-end tests need the compose stack's values`)
  return value
}

export const env = {
  port,
  baseDomain,
  /** The Platform Shell's host, where an operator works. */
  platformHost: process.env.PLATFORM_HOSTNAME ?? `platform.${baseDomain}`,
  /** A Tenant's host, which is how the backend picks the tenant. */
  tenantHost: (code: string) => `${code}.${baseDomain}`,
  /** A host's origin through Caddy; the hostname is what picks the tenant or the Platform. */
  originFor: (host: string) => `http://${host}:${port}`,
  consoleUrl: (host: string) => `http://${host}:${port}/console/`,
  keycloak: process.env.KEYCLOAK_URL ?? 'http://localhost:8081',
  realm: process.env.KEYCLOAK_REALM ?? 'tenancykit',
  /** The console's public client, whose redirect URIs follow the hosts Platform manages. */
  consoleClientId: process.env.CONSOLE_KEYCLOAK_CLIENT_ID ?? 'suite-console',
  platformAdmin: {
    email: process.env.PLATFORM_ADMIN_EMAIL ?? required('PLATFORM_BOOTSTRAP_ADMIN_EMAIL'),
    password: process.env.PLATFORM_ADMIN_PASSWORD ?? required('PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD'),
  },
  smokeClient: {
    id: process.env.SMOKE_CLIENT_ID ?? 'suite-smoke',
    secret: required('SMOKE_CLIENT_SECRET'),
  },
  keycloakAdmin: {
    id: process.env.PLATFORM_KEYCLOAK_ADMIN_CLIENT_ID ?? 'platform-admin',
    secret: required('PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET'),
  },
  frontendCi: {
    id: process.env.PLATFORM_FRONTEND_CI_SERVICE_CLIENT_ID ?? 'frontend-ci',
    secret: process.env.FRONTEND_CI_SERVICE_CLIENT_SECRET ?? '',
  },
} as const

/** The Keycloak username Platform gives an account: `<scope>:<email>`, with `platform` for the suite's own. */
export function keycloakUsername(scope: string | null, email: string): string {
  return scope ? `${scope}:${email}` : email
}

/** A fresh tenant code per run, so two runs never collide and a rerun never reuses another test's records. */
export function uniqueCode(prefix: string): string {
  const suffix = `${Date.now().toString(36).slice(-4)}${Math.floor(Math.random() * 1296).toString(36)}`
  return `${prefix}-${suffix}`
}
