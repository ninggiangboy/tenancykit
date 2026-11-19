import { federation } from '@module-federation/vite'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import type { UserConfig } from 'vite'

/**
 * The versions a shell and a remote must agree on, owned here so one edit moves every build. `@suite/ui` is absent on
 * purpose: each remote bundles its own copy, so a remote deploy never waits for a shell release.
 */
export const SHARED_SINGLETONS: Record<string, { singleton: boolean; requiredVersion: string }> = {
  react: { singleton: true, requiredVersion: '^19.3.0' },
  'react-dom': { singleton: true, requiredVersion: '^19.3.0' },
  '@tanstack/react-query': { singleton: true, requiredVersion: '^5.107.0' },
  '@tanstack/react-router': { singleton: true, requiredVersion: '^1.170.0' },
}

export interface ShellConfigOptions {
  /** The federation name. */
  name: string
  /** The dev-server port; strict, so a stale process is a failure rather than a second server. */
  port?: number
  /** Where the dev server proxies `/platform` and the runtime `config.json`; the compose stack's Caddy by default. */
  platformOrigin?: string
  outDir?: string
}

/**
 * A shell serves under `/console/` and learns its remotes at runtime, so it declares none at build time: shell-core
 * registers them from the registry feed with the MF runtime.
 */
export function shellConfig({
  name,
  port = 5173,
  platformOrigin = 'http://localhost:8090',
  outDir = 'dist',
}: ShellConfigOptions): UserConfig {
  return {
    base: '/console/',
    plugins: [
      react(),
      tailwindcss(),
      federation({
        name,
        filename: 'remoteEntry.js',
        remotes: {},
        shared: SHARED_SINGLETONS,
        manifest: true,
        dts: false,
      }),
    ],
    server: {
      port,
      strictPort: true,
      proxy: {
        '/platform': { target: platformOrigin },
        // The static server renders this per environment; without the proxy Vite answers with index.html and the
        // shell dies on a JSON parse error before it paints anything.
        '/console/config.json': { target: platformOrigin },
        // The registry hands the shell the manifest URL of each published remote version, an absolute path on the
        // shell's own origin; the static server behind the gateway holds those builds, not Vite.
        '/console/remotes': { target: platformOrigin },
      },
    },
    build: { target: 'esnext', outDir },
  }
}
