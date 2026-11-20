import type { Locale, NavSection, ShellContext, Theme } from '@suite/shell-sdk'
import { createContext, useContext } from 'react'
import type { CommandRegistry } from '../commands'
import type { ConsoleRemote, MeResponse } from '../contracts'
import type { ShellMessageKey } from '../i18n'
import type { RemoteLoader } from '../remote-loader.types'

/** Everything the frame's parts read: who is signed in, which remotes exist, what each registered as its screens. */
export interface Frame {
  definition: { audience: 'PLATFORM' | 'TENANT'; title: string }
  me: MeResponse
  remotes: ConsoleRemote[]
  loader: RemoteLoader
  context: ShellContext
  registry: CommandRegistry
  /** The sections each mounted remote registered, by remote name. */
  navigation: Record<string, NavSection[]>
  setNavigation: (remote: string, sections: NavSection[] | null) => void
  locale: Locale
  theme: Theme
  collapsed: boolean
  onToggleSidebar: () => void
  onTheme: (theme: Theme) => void
  onLocale: (locale: Locale) => void
  onSignOut?: () => void
  onOpenPalette: () => void
  t: (key: ShellMessageKey) => string
}

export const FrameContext = createContext<Frame | null>(null)

export function useFrame(): Frame {
  const frame = useContext(FrameContext)
  if (!frame) {
    throw new Error('the shell frame is missing its context')
  }
  return frame
}

// The router may report the path with or without its `/console` basepath.
const REMOTE_PATH = /^(?:\/console)?\/r\/([^/]+)(\/.*)?$/

/** The remote and the screen the URL is on: `/console/r/<remote>/<rest>`. */
export function currentPlace(pathname: string, frame: Pick<Frame, 'remotes' | 'navigation'>) {
  const match = REMOTE_PATH.exec(pathname)
  if (!match?.[1]) return { remote: undefined, item: undefined }
  const name = match[1]
  const remote = frame.remotes.find((entry) => entry.name === name)
  const rest = match[2] ?? '/'
  const item = (frame.navigation[name] ?? [])
    .flatMap((section) => section.items)
    .filter((entry) => rest === entry.to || rest.startsWith(`${entry.to}/`))
    .sort((left, right) => right.to.length - left.to.length)[0]
  return { remote, item }
}

/** Below the `md` breakpoint the sidebar is a drawer, closed until asked for. */
export function isNarrowViewport(): boolean {
  return globalThis.matchMedia?.('(max-width: 767px)').matches ?? false
}
