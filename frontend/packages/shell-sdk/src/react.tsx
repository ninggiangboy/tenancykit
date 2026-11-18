import { createContext, type ReactNode, useContext, useEffect } from 'react'
import type { CommandItem, ShellContext } from './index'

const ShellContextValue = createContext<ShellContext | null>(null)

/** The shell puts one of these around everything it mounts, remotes included. */
export function ShellProvider({ ctx, children }: { ctx: ShellContext; children: ReactNode }) {
  return <ShellContextValue.Provider value={ctx}>{children}</ShellContextValue.Provider>
}

export function useShell(): ShellContext {
  const context = useContext(ShellContextValue)
  if (!context) {
    throw new Error('a remote renders inside the shell context; mount it through the shell')
  }
  return context
}

/** Whether the signed-in user holds a code. The backend still decides; this only hides a control. */
export function can(ctx: ShellContext, code: string): boolean {
  return ctx.user.permissions.includes(code)
}

export function usePermission(code: string): boolean {
  return can(useShell(), code)
}

/**
 * Adds palette items for the lifetime of the component: a remote registers its actions on mount and takes them back on
 * unmount, so a palette never offers a screen that is no longer loaded.
 */
export function useRemoteCommands(items: CommandItem[]): void {
  const { registerCommands } = useShell()
  useEffect(() => registerCommands(items), [registerCommands, items])
}
