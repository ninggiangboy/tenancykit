import type { ShellDefinition } from './contracts'

export { type BootedShell, bootShell } from './boot'
export { type CommandRegistry, createCommandRegistry } from './commands'
export { CONFIG_URL, type ConsoleConfig, loadConsoleConfig } from './config'
export {
  BootRefused,
  type ConsoleRemote,
  type MeResponse,
  refusalFor,
  type ShellDefinition,
  tenantFromHost,
} from './contracts'
export { createShellI18n, type ShellMessageKey, shellResources } from './i18n'
export { createRemoteLoader, type FederationRuntime, type RemoteLoaderOptions, RemoteRefused } from './remote-loader'
export type { RemoteLoader } from './remote-loader.types'
export { ShellApp, type ShellAppProps } from './ShellApp'
export { type ShellAuth, type StartShellOptions, startShell } from './startShell'
export { applyTheme, preferredLocale, preferredTheme, resolveTheme, saveLocale, saveTheme } from './theme'

export const PLATFORM_SHELL: ShellDefinition = { audience: 'PLATFORM', title: 'Platform' }
export const TENANT_SHELL: ShellDefinition = { audience: 'TENANT', title: 'Console' }
