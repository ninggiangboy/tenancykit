import { execFileSync } from 'node:child_process'
import { readdirSync } from 'node:fs'
import { join } from 'node:path'
import { env, frontendDir, repoRoot } from './env'

/** A version a test publishes: the commit plus a label, so a rerun of the same commit publishes a new directory. */
export function versionFor(label: string): string {
  const sha = execFileSync('git', ['-C', repoRoot, 'rev-parse', '--short', 'HEAD']).toString().trim()
  return `0.0.0+${sha}-${label}-${Date.now().toString(36).slice(-4)}`
}

/** Builds one remote with the version its asset URLs and its `console.json` declare. */
export function buildRemote(name: string, version: string): void {
  execFileSync('pnpm', ['--filter', `@suite/remote-${name}`, 'build'], {
    cwd: frontendDir,
    env: { ...process.env, REMOTE_VERSION: version },
    stdio: 'pipe',
  })
}

function ciSecret(): string {
  const secret = process.env.FRONTEND_CI_SERVICE_CLIENT_SECRET ?? env.frontendCi.secret
  if (!secret) throw new Error('FRONTEND_CI_SERVICE_CLIENT_SECRET is not set; publishing needs the frontend-ci client')
  return secret
}

/** Publishes the built remote and registers it with Platform; registering never activates it. */
export function publishRemote(name: string, version: string): void {
  execFileSync(join(repoRoot, 'ops/console/deploy-remote.sh'), [name, join(frontendDir, 'remotes', name, 'dist')], {
    cwd: repoRoot,
    env: {
      ...process.env,
      REMOTE_VERSION_OVERRIDE: version,
      FRONTEND_CI_SERVICE_CLIENT_ID: env.frontendCi.id,
      FRONTEND_CI_SERVICE_CLIENT_SECRET: ciSecret(),
      CONSOLE_BASE_URL: env.originFor(env.platformHost),
    },
    stdio: 'pipe',
  })
}

/** The shell's asset URLs as the served HTML names them; a redeploy of a remote must leave every one unchanged. */
export async function shellAssetUrls(host: string): Promise<string[]> {
  const answer = await fetch(`${env.consoleUrl(host)}`, { headers: { accept: 'text/html' } })
  if (!answer.ok) throw new Error(`the shell of ${host} answered ${answer.status}`)
  const html = await answer.text()
  return [...html.matchAll(/\/console\/assets\/[A-Za-z0-9_.-]+/g)].map((match) => match[0]).sort()
}

/** Runs SQL against the stack's PostgreSQL; only the compose stack exposes its database to a test. */
export function psql(database: 'platform' | 'billing', sql: string): void {
  const user = database === 'platform' ? 'platform_owner' : 'billing_owner'
  execFileSync(
    'docker',
    [
      'compose',
      '--project-directory',
      repoRoot,
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      user,
      '-d',
      database,
      '-c',
      sql,
    ],
    { cwd: repoRoot, stdio: 'pipe' },
  )
}

/**
 * Removes a published version's directory from the console volume, which is how the extension trace cleans up. The
 * static server mounts the volume read-only, so the write goes through the same sidecar the publish uses.
 */
export function removePublishedFiles(name: string, version: string): void {
  execFileSync(
    'docker',
    [
      'compose',
      '--project-directory',
      repoRoot,
      '--profile',
      'tools',
      'run',
      '--rm',
      '--no-deps',
      '--entrypoint',
      'sh',
      'console-deploy',
      '-c',
      `rm -rf /srv/remotes/${name}/${version}`,
    ],
    { cwd: repoRoot, stdio: 'pipe' },
  )
}

/** The remote directories a throwaway remote may be copied into, so cleanup never deletes a real one. */
export function remoteDirectories(): string[] {
  return readdirSync(join(frontendDir, 'remotes'))
}
