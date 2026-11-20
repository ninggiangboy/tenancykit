import type { RouterHistory } from '@tanstack/react-router'
import type { ComponentType } from 'react'

/** Bumped only for a breaking change in this contract; a shell refuses a remote built against another major. */
export const SDK_MAJOR = 1

export type Locale = 'en' | 'vi'
export type Theme = 'light' | 'dark' | 'system'
export type Scope = 'PLATFORM' | 'TENANT'

/** The signed-in user as `/me` reports them. The backend still decides what every request may do. */
export interface ShellUser {
  id: string
  email: string
  displayName: string
  scope: Scope
  tenantId: string | null
  permissions: readonly string[]
}

export interface ShellTenant {
  id: string
  code: string
  name: string
}

/** An entry a remote adds to the shell's command palette. */
export interface CommandItem {
  id: string
  label: string
  keywords?: string[]
  group?: string
  shortcut?: string
  onSelect: () => void
}

/** One screen of a remote; `to` is relative to the remote's basepath, for example `/customers`. */
export interface NavItem {
  id: string
  label: string
  to: string
  /** A Lucide icon name from `@suite/ui`'s `IconName`; the shell falls back to `file`. */
  icon?: string
}

/** A titled group of a remote's screens; the shell lists them under the app in its sidebar. */
export interface NavSection {
  id: string
  label?: string
  items: NavItem[]
}

/**
 * What a shell gives a remote. The remote never imports the shell, the auth library or another remote: this object and
 * `@suite/ui` are the whole seam.
 */
export interface ShellContext {
  /** The remote's path prefix, for example `/console/r/billing`; the remote's router uses it as its basepath. */
  basepath: string
  /** The shell's browser history. A remote builds its router on it, so a remote navigation leaves the shell mounted. */
  history: RouterHistory
  /** Always resolves to a token the shell holds in memory, refreshing it when it is near expiry. */
  getToken: () => Promise<string>
  user: ShellUser
  tenant: ShellTenant | null
  theme: Theme
  locale: Locale
  /** Navigates within the console, keeping the browser history the shell owns. */
  navigate: (to: string) => void
  /** Registers palette items; returns the removal. A remote calls it on mount and removes on unmount. */
  registerCommands: (items: CommandItem[]) => () => void
  /**
   * Hands the shell the remote's screens for its sidebar and breadcrumb; returns the removal. Optional on both sides:
   * a shell without it leaves the remote to render its own section nav, and a remote that never calls it shows none in
   * the sidebar. Adding it did not change the SDK major.
   */
  registerNavigation?: (sections: NavSection[]) => () => void
}

/** What a remote module must export from its `./app` entry. */
export interface RemoteModule {
  name: string
  sdkMajor: number
  Root: ComponentType<{ ctx: ShellContext }>
}

/** Identity helper: it types the module a remote exports and nothing else. */
export function defineRemote(module: RemoteModule): RemoteModule {
  return module
}

/** A shell mounts only a remote whose major matches its own. */
export function isCompatible(sdkMajor: number): boolean {
  return sdkMajor === SDK_MAJOR
}
