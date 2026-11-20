import type { Locale, NavSection, ShellContext, Theme } from '@suite/shell-sdk'
import { Toaster, UiMessagesProvider, useHotkey, useToasts } from '@suite/ui'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
  useLocation,
} from '@tanstack/react-router'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { I18nextProvider } from 'react-i18next'
import { type CommandRegistry, createCommandRegistry } from './commands'
import type { ConsoleRemote, MeResponse } from './contracts'
import { currentPlace, type Frame, FrameContext, isNarrowViewport, useFrame } from './frame/frame-context'
import { Header } from './frame/header'
import { Home, NotFound } from './frame/home'
import { Palette } from './frame/palette'
import { RemoteHost } from './frame/remote-host'
import { Sidebar } from './frame/sidebar'
import { createShellI18n, type ShellMessageKey } from './i18n'
import type { RemoteLoader } from './remote-loader.types'
import {
  applyDensity,
  applyTheme,
  type Density,
  preferredDensity,
  preferredLocale,
  preferredTheme,
  saveDensity,
  saveLocale,
  saveTheme,
} from './theme'

export interface ShellAppProps {
  definition: { audience: 'PLATFORM' | 'TENANT'; title: string }
  me: MeResponse
  remotes: ConsoleRemote[]
  history?: ReturnType<typeof createBrowserHistory>
  loader: RemoteLoader
  getToken: () => Promise<string>
  registry?: CommandRegistry
  initialTheme?: Theme
  initialLocale?: Locale
  onSignOut?: () => void
}

/**
 * Both shells: the frame, the remote host, the palette and the two catalogs.
 *
 * The router and the frame are deliberately ordered this way: the frame's header reads the current route, so it renders
 * inside the router, while the frame's state stays in this component and reaches the router through a stable context.
 */
export function ShellApp({
  definition,
  me,
  remotes,
  history: historyProp,
  loader,
  getToken,
  registry: registryProp,
  initialTheme,
  initialLocale,
  onSignOut,
}: ShellAppProps) {
  // Created once: a default made in the parameter list would be a new history and registry on every render, which
  // rebuilds the router (remounting the remote) and drops the commands a remote registered.
  const [history] = useState(() => historyProp ?? createBrowserHistory())
  const [registry] = useState(() => registryProp ?? createCommandRegistry())
  const [theme, setTheme] = useState<Theme>(initialTheme ?? preferredTheme())
  const [density, setDensity] = useState<Density>(preferredDensity)
  const [locale, setLocale] = useState<Locale>(initialLocale ?? preferredLocale())
  const [collapsed, setCollapsed] = useState(isNarrowViewport)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [navigation, setNavigationState] = useState<Record<string, NavSection[]>>({})
  const [toasts, , dismissToast] = useToasts()
  const i18n = useMemo(() => createShellI18n(locale), [locale])
  const t = useCallback(
    (key: ShellMessageKey, values?: Record<string, unknown>) =>
      String(i18n.t(key as string, values as Record<string, unknown>)),
    [i18n],
  )
  const queryClient = useMemo(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }), [])

  useEffect(() => {
    applyTheme(theme)
    saveTheme(theme)
  }, [theme])

  useEffect(() => {
    applyDensity(density)
    saveDensity(density)
  }, [density])

  const setNavigation = useCallback((remote: string, sections: NavSection[] | null) => {
    setNavigationState((current) => {
      const next = { ...current }
      if (sections) next[remote] = sections
      else delete next[remote]
      return next
    })
  }, [])

  const context = useMemo<ShellContext>(() => {
    const tenantCode = globalThis.location?.hostname.split('.')[0] ?? ''
    return {
      basepath: '/console/r',
      history,
      getToken,
      user: {
        id: me.id,
        email: me.email,
        displayName: me.displayName,
        scope: me.scope,
        tenantId: me.tenantId,
        permissions: me.permissions,
      },
      tenant: me.scope === 'TENANT' && me.tenantId ? { id: me.tenantId, code: tenantCode, name: tenantCode } : null,
      theme,
      locale,
      navigate: (to) => void history.push(to),
      registerCommands: (items) => registry.register(items),
    }
  }, [me, history, getToken, theme, locale, registry])

  const frame = useMemo<Frame>(
    () => ({
      definition,
      me,
      remotes,
      loader,
      context,
      registry,
      navigation,
      setNavigation,
      locale,
      theme,
      density,
      collapsed,
      onToggleSidebar: () => setCollapsed((value) => !value),
      drawerOpen,
      onToggleDrawer: () => setDrawerOpen((value) => !value),
      onTheme: setTheme,
      onDensity: setDensity,
      onLocale: (next) => {
        setLocale(next)
        saveLocale(next)
      },
      onSignOut,
      onOpenPalette: () => setPaletteOpen(true),
      t,
    }),
    [
      definition,
      me,
      remotes,
      loader,
      context,
      registry,
      navigation,
      setNavigation,
      locale,
      theme,
      density,
      collapsed,
      drawerOpen,
      onSignOut,
      t,
    ],
  )

  const router = useMemo(() => buildRouter({ remotes, loader, history }), [remotes, loader, history])
  useHotkey('mod+k', () => setPaletteOpen((open) => !open), true)

  return (
    <I18nextProvider i18n={i18n}>
      <UiMessagesProvider locale={locale}>
        <QueryClientProvider client={queryClient}>
          <FrameContext.Provider value={frame}>
            <RouterProvider router={router} />
            <Palette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
            <Toaster toasts={toasts} onDismiss={dismissToast} />
          </FrameContext.Provider>
        </QueryClientProvider>
      </UiMessagesProvider>
    </I18nextProvider>
  )
}

/** The frame renders inside the router, because the header reads the route and the sidebar's links are router links. */
function FrameLayout() {
  const frame = useFrame()
  const pathname = useLocation({ select: (location) => location.pathname })
  const appCode = currentPlace(pathname, frame).remote?.appCode

  // `<html>` and not this div: Radix portals the sheet, the dialogs and the menus into <body>, outside the frame.
  useEffect(() => {
    const root = document.documentElement
    if (appCode) root.dataset.app = appCode
    else delete root.dataset.app
    return () => {
      delete root.dataset.app
    }
  }, [appCode])

  return (
    <div className="flex min-h-dvh bg-sidebar text-foreground">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col p-2 pl-0">
        <Header />
        <main className="relative min-h-0 flex-1 overflow-auto rounded-xl border border-border bg-background">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function buildRouter({
  remotes,
  loader,
  history,
}: {
  remotes: ConsoleRemote[]
  loader: RemoteLoader
  history: ReturnType<typeof createBrowserHistory>
}) {
  const root = createRootRoute({
    component: FrameLayout,
    notFoundComponent: () => <NotFound />,
  })
  const home = createRoute({
    getParentRoute: () => root,
    path: '/',
    component: () => <Home />,
  })
  // Two routes for one host: `/r/<name>` (what the sidebar links to) and `/r/<name>/...` (a remote's own routes).
  const remoteRoot = createRoute({
    getParentRoute: () => root,
    path: '/r/$remote',
    component: () => <RemoteHost remotes={remotes} loader={loader} />,
  })
  const remoteSplat = createRoute({
    getParentRoute: () => root,
    path: '/r/$remote/$',
    component: () => <RemoteHost remotes={remotes} loader={loader} />,
  })
  return createRouter({ routeTree: root.addChildren([home, remoteRoot, remoteSplat]), basepath: '/console', history })
}
