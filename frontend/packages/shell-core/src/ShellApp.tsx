import type { Locale, ShellContext, Theme } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import {
  AppMark,
  CommandPalette,
  DropdownMenu,
  EmptyState,
  ErrorState,
  humanize,
  Icon,
  Kbd,
  Skeleton,
  Toaster,
  UiMessagesProvider,
  useHotkey,
  useToasts,
} from '@suite/ui'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import {
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Link,
  Outlet,
  RouterProvider,
  useParams,
} from '@tanstack/react-router'
import {
  Component,
  createContext,
  type ErrorInfo,
  type ReactNode,
  Suspense,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react'
import { I18nextProvider } from 'react-i18next'
import { type CommandRegistry, createCommandRegistry } from './commands'
import type { ConsoleRemote, MeResponse } from './contracts'
import { createShellI18n } from './i18n'
import type { RemoteLoader } from './remote-loader.types'
import { applyTheme, preferredLocale, preferredTheme, saveLocale, saveTheme } from './theme'

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

interface Frame {
  definition: ShellAppProps['definition']
  me: MeResponse
  remotes: ConsoleRemote[]
  loader: RemoteLoader
  context: ShellContext
  registry: CommandRegistry
  locale: Locale
  theme: Theme
  collapsed: boolean
  onToggleSidebar: () => void
  onTheme: (theme: Theme) => void
  onLocale: (locale: Locale) => void
  onSignOut?: () => void
  onOpenPalette: () => void
  t: (key: string) => string
}

const FrameContext = createContext<Frame | null>(null)

function useFrame(): Frame {
  const frame = useContext(FrameContext)
  if (!frame) {
    throw new Error('the shell frame is missing its context')
  }
  return frame
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
  history = createBrowserHistory(),
  loader,
  getToken,
  registry = createCommandRegistry(),
  initialTheme,
  initialLocale,
  onSignOut,
}: ShellAppProps) {
  const [theme, setTheme] = useState<Theme>(initialTheme ?? preferredTheme())
  const [locale, setLocale] = useState<Locale>(initialLocale ?? preferredLocale())
  const [collapsed, setCollapsed] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [toasts, , dismissToast] = useToasts()
  const i18n = useMemo(() => createShellI18n(locale), [locale])
  const t = useCallback((key: string) => String(i18n.t(key)), [i18n])
  const queryClient = useMemo(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }), [])

  useEffect(() => {
    applyTheme(theme)
    saveTheme(theme)
  }, [theme])

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
      locale,
      theme,
      collapsed,
      onToggleSidebar: () => setCollapsed((value) => !value),
      onTheme: setTheme,
      onLocale: (next) => {
        setLocale(next)
        saveLocale(next)
      },
      onSignOut,
      onOpenPalette: () => setPaletteOpen(true),
      t,
    }),
    [definition, me, remotes, loader, context, registry, locale, theme, collapsed, onSignOut, t],
  )

  const router = useMemo(() => buildRouter({ remotes, loader }), [remotes, loader])
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

function Sidebar() {
  const { definition, me, remotes, collapsed, onToggleSidebar, onSignOut, t } = useFrame()
  const tenantCode = globalThis.location?.hostname.split('.')[0] ?? ''
  return (
    <aside className={collapsed ? 'flex w-12 shrink-0 flex-col gap-2 p-1.5' : 'flex w-60 shrink-0 flex-col gap-2 p-2'}>
      <div className="flex items-center gap-2 px-1 py-1.5">
        {definition.audience === 'PLATFORM' ? (
          <>
            <span className="flex size-6 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Icon name="shield" size={14} />
            </span>
            {!collapsed && <span className="text-body-strong">{definition.title}</span>}
          </>
        ) : (
          <>
            <span className="flex size-6 items-center justify-center rounded-md bg-primary text-caption font-semibold text-primary-foreground">
              {tenantCode.slice(0, 1).toUpperCase()}
            </span>
            {!collapsed && (
              <span className="flex min-w-0 flex-col">
                <span className="truncate text-body-strong">{tenantCode}</span>
                <span className="truncate text-caption text-muted-foreground">{globalThis.location?.hostname}</span>
              </span>
            )}
          </>
        )}
        <button
          type="button"
          onClick={onToggleSidebar}
          aria-label={t('shell.toggleSidebar')}
          className="ml-auto rounded-md p-1 text-muted-foreground hover:bg-sidebar-accent hover:text-foreground"
        >
          <Icon name="panel-left" size={14} />
        </button>
      </div>

      <nav aria-label={t('shell.remotes')} className="flex flex-col gap-0.5">
        {remotes.map((remote) => (
          <Link
            key={remote.name}
            to="/r/$remote/$"
            params={{ remote: remote.name, _splat: '' }}
            activeProps={{ className: 'bg-sidebar-accent text-foreground' }}
            className="flex items-center gap-2 rounded-md px-2 py-1.5 text-body text-sidebar-foreground hover:bg-sidebar-accent"
          >
            <AppMark app={remote.appCode} size="sm" />
            {!collapsed && <span className="truncate">{remote.title}</span>}
          </Link>
        ))}
      </nav>

      <div className="mt-auto flex items-center gap-2 rounded-md px-2 py-1.5">
        <span className="flex size-6 items-center justify-center rounded-full bg-secondary text-caption">
          {me.displayName.slice(0, 1).toUpperCase()}
        </span>
        {!collapsed && (
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate text-small">{me.displayName}</span>
            <span className="truncate text-caption text-muted-foreground">{me.email}</span>
          </span>
        )}
        {onSignOut && (
          <button
            type="button"
            onClick={onSignOut}
            aria-label={t('shell.signOut')}
            className="rounded-md p-1 text-muted-foreground hover:bg-sidebar-accent hover:text-foreground"
          >
            <Icon name="log-out" size={14} />
          </button>
        )}
      </div>
    </aside>
  )
}

/** The frame renders inside the router, because the header reads the route and the sidebar's links are router links. */
function FrameLayout() {
  const frame = useFrame()
  return (
    <div className="flex min-h-dvh bg-sidebar text-foreground">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col p-2 pl-0">
        <Header />
        <main className="relative min-h-0 flex-1 overflow-auto rounded-xl border border-border bg-background">
          {frame.definition.audience === 'PLATFORM' && (
            <span aria-hidden className="absolute inset-x-0 top-0 h-0.5 rounded-t-xl bg-scope-platform" />
          )}
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function Header() {
  const { definition, me, onToggleSidebar, onOpenPalette, onTheme, locale, onLocale, onSignOut, t } = useFrame()
  const { remote } = useParams({ strict: false }) as { remote?: string }
  const title = remote ? humanize(remote) : t('shell.home')
  return (
    <div className="flex h-13 items-center gap-2 px-2">
      <button
        type="button"
        onClick={onToggleSidebar}
        aria-label={t('shell.toggleSidebar')}
        className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
      >
        <Icon name="panel-left" size={14} />
      </button>
      <nav aria-label="Breadcrumb" className="flex items-center gap-1 text-small">
        <span className="text-muted-foreground">{definition.title}</span>
        <Icon name="chevron-right" size={12} className="text-muted-foreground" />
        <span className="text-body-strong">{title}</span>
      </nav>
      {definition.audience === 'PLATFORM' && (
        <span className="ml-2 inline-flex h-5 items-center gap-1.5 rounded-full border border-border px-2 text-caption">
          <span aria-hidden className="size-1.5 rounded-full bg-scope-platform" />
          {t('shell.platformScope')}
        </span>
      )}
      <div className="ml-auto flex items-center gap-1">
        <button
          type="button"
          onClick={onOpenPalette}
          className="inline-flex h-7 items-center gap-2 rounded-md border border-input bg-background px-2 text-small text-muted-foreground shadow-xs hover:bg-accent"
        >
          <Icon name="search" size={14} />
          <span className="hidden sm:inline">{t('shell.search')}</span>
          <Kbd keys={['mod', 'k']} />
        </button>
        <DropdownMenu
          label={t('shell.theme')}
          items={[
            { label: t('shell.light'), icon: 'sparkle', onSelect: () => onTheme('light') },
            { label: t('shell.dark'), icon: 'ring', onSelect: () => onTheme('dark') },
            { label: t('shell.system'), icon: 'settings', onSelect: () => onTheme('system') },
          ]}
          trigger={
            <button
              type="button"
              aria-label={t('shell.theme')}
              className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <Icon name="sparkle" size={14} />
            </button>
          }
        />
        <DropdownMenu
          label={t('shell.language')}
          items={[
            { label: t('shell.english'), onSelect: () => onLocale('en') },
            { label: t('shell.vietnamese'), onSelect: () => onLocale('vi') },
          ]}
          trigger={
            <button
              type="button"
              aria-label={`${t('shell.language')}: ${locale.toUpperCase()}`}
              className="rounded-md px-1.5 py-1 text-caption text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              {locale.toUpperCase()}
            </button>
          }
        />
        {onSignOut && (
          <DropdownMenu
            label={t('shell.user')}
            items={[{ heading: me.email }, { label: t('shell.signOut'), icon: 'log-out', onSelect: onSignOut }]}
            trigger={
              <button
                type="button"
                aria-label={t('shell.user')}
                className="flex size-6 items-center justify-center rounded-full bg-secondary text-caption"
              >
                {me.displayName.slice(0, 1).toUpperCase()}
              </button>
            }
          />
        )}
      </div>
    </div>
  )
}

function Palette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { remotes, registry, definition, context, t } = useFrame()
  const groups = useMemo(
    () => [
      {
        label: t('shell.remotes'),
        items: remotes.map((remote) => ({
          id: remote.name,
          label: remote.title,
          hint: remote.appCode,
          icon: 'grid' as const,
          onSelect: () => context.navigate(`/console/r/${remote.name}/`),
        })),
      },
      {
        label: t('shell.commands'),
        items: registry.items().map((item) => ({
          id: item.id,
          label: item.label,
          keywords: item.keywords?.join(' '),
          shortcut: item.shortcut?.split('+'),
          onSelect: item.onSelect,
        })),
      },
    ],
    [remotes, registry, context, t],
  )
  return (
    <CommandPalette
      open={open}
      onClose={onClose}
      groups={groups}
      placeholder={t('shell.search')}
      scope={
        definition.audience === 'PLATFORM' ? (
          <span className="text-caption text-muted-foreground">{t('shell.platformScope')}</span>
        ) : null
      }
    />
  )
}

function buildRouter({ remotes, loader }: { remotes: ConsoleRemote[]; loader: RemoteLoader }) {
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
  return createRouter({ routeTree: root.addChildren([home, remoteRoot, remoteSplat]), basepath: '/console' })
}

function Home() {
  const { definition, remotes, t } = useFrame()
  if (remotes.length === 0) {
    return (
      <EmptyState
        icon="grid"
        title={definition.audience === 'TENANT' ? t('shell.noApps') : t('shell.home')}
        description={t('shell.noAppsHint')}
      />
    )
  }
  return <EmptyState icon="grid" title={t('shell.home')} description={t('shell.welcome')} />
}

function NotFound() {
  const { t } = useFrame()
  return <EmptyState icon="file" title={t('shell.notFound')} description={t('shell.notFoundHint')} />
}

function RemoteHost({ remotes, loader }: { remotes: ConsoleRemote[]; loader: RemoteLoader }) {
  const { context, t } = useFrame()
  const params = useParams({ strict: false }) as { remote?: string }
  const remote = remotes.find((entry) => entry.name === params.remote)
  const query = useQuery({
    queryKey: ['remote', remote?.name, remote?.version],
    queryFn: () => loader.load(remote as ConsoleRemote),
    enabled: Boolean(remote),
    retry: false,
  })

  if (!remote) {
    return <NotFound />
  }
  if (query.isError) {
    return (
      <div className="p-6">
        <ErrorState
          title={t('shell.remoteFailed')}
          description={`${t('shell.remoteFailedHint')} ${String((query.error as Error).message)}`}
          onRetry={() => void query.refetch()}
        />
      </div>
    )
  }
  if (!query.data) {
    return (
      <div className="flex flex-col gap-2 p-6">
        <Skeleton width="30%" height={18} />
        <Skeleton width="60%" />
        <Skeleton width="50%" />
      </div>
    )
  }

  const remoteContext: ShellContext = { ...context, basepath: `/console/r/${remote.name}` }
  return (
    <div className="contents" data-remote={remote.name} data-remote-version={remote.version}>
      <RemoteBoundary onRetry={() => void query.refetch()} label={t('shell.remoteFailed')}>
        <ShellProvider ctx={remoteContext}>
          <Suspense fallback={<Skeleton width="100%" height={24} />}>
            <query.data.Root ctx={remoteContext} />
          </Suspense>
        </ShellProvider>
      </RemoteBoundary>
    </div>
  )
}

class RemoteBoundary extends Component<
  { children: ReactNode; onRetry: () => void; label: string },
  { error: Error | null }
> {
  override state = { error: null as Error | null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('the console refused a remote that threw on mount', error, info)
  }

  override render() {
    if (this.state.error) {
      return (
        <div className="p-6">
          <ErrorState
            title={this.props.label}
            description={this.state.error.message}
            onRetry={() => {
              this.setState({ error: null })
              this.props.onRetry()
            }}
          />
        </div>
      )
    }
    return this.props.children
  }
}
