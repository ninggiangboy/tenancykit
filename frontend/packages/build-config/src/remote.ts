import { federation } from '@module-federation/vite'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import type { UserConfig } from 'vite'
import { SHARED_SINGLETONS } from './shell.ts'

/** The version a remote build declares to the registry: `<package version>+<short sha>`, or `dev` while developing. */
export function remoteVersion(): string {
  return process.env.REMOTE_VERSION ?? 'dev'
}

export interface RemoteConfigOptions {
  /** The registry name; it fixes the entry path the registry allow-lists. */
  name: string
  /** The version this build is served under. Defaults to {@link remoteVersion}. */
  version?: string
  /** The shell-sdk major this build was compiled against; the registry stores it and a shell refuses another. */
  sdkMajor?: number
  port?: number
  outDir?: string
}

/**
 * A remote is served from an immutable, versioned directory. In dev it lives at the root so a standalone remote runs
 * without a shell; in a build its asset URLs already carry `/console/remotes/<name>/<version>/`, which is exactly
 * what the registry accepts as an entry path.
 */
export function remoteConfig({
  name,
  version = remoteVersion(),
  sdkMajor = 1,
  port = 5174,
  outDir = 'dist',
}: RemoteConfigOptions): UserConfig {
  return {
    base: version === 'dev' ? '/' : `/console/remotes/${name}/${version}/`,
    define: { __REMOTE_VERSION__: JSON.stringify(version) },
    plugins: [
      react(),
      tailwindcss(),
      // The deploy step reads this instead of guessing: the name, the version this build was made for and the
      // shell-sdk major it was compiled against are the three facts the registry needs.
      {
        name: 'console-remote-manifest',
        generateBundle() {
          this.emitFile({
            type: 'asset',
            fileName: 'console.json',
            source: JSON.stringify({ name, version, sdkMajor }, null, 2),
          })
        },
      },
      federation({
        name,
        filename: 'remoteEntry.js',
        exposes: { './app': './src/app.tsx' },
        shared: SHARED_SINGLETONS,
        manifest: true,
        dts: false,
      }),
    ],
    server: { port, strictPort: true, cors: true, origin: `http://localhost:${port}` },
    build: { target: 'esnext', outDir },
  }
}
