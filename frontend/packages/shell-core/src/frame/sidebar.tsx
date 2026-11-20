import { AppMark, cn, Icon } from '@suite/ui'
import { Link, useLocation } from '@tanstack/react-router'
import { useEffect } from 'react'
import { currentPlace, isNarrowViewport, useFrame } from './frame-context'

/** The apps, and under the open app its screens grouped as the remote registered them. */
export function Sidebar() {
  const frame = useFrame()
  const { definition, me, remotes, navigation, collapsed, onToggleSidebar, onSignOut, t } = frame
  const pathname = useLocation({ select: (location) => location.pathname })
  const place = currentPlace(pathname, frame)
  // A phone's drawer closes once a screen was chosen. It reads `collapsed` when the path changes, not on every toggle.
  // biome-ignore lint/correctness/useExhaustiveDependencies: only a new path closes the drawer.
  useEffect(() => {
    if (isNarrowViewport() && !collapsed) onToggleSidebar()
  }, [pathname])
  const tenantCode = globalThis.location?.hostname.split('.')[0] ?? ''

  return (
    <aside
      className={cn(
        'sticky top-0 flex h-dvh shrink-0 flex-col gap-3 overflow-y-auto',
        // Open on a phone, the sidebar lies over the page instead of squeezing it.
        collapsed
          ? 'w-12 p-1.5'
          : 'w-60 p-2 max-md:fixed max-md:left-0 max-md:z-30 max-md:border-r max-md:border-border max-md:bg-sidebar max-md:shadow-xl',
      )}
    >
      <div className="flex items-center gap-2 px-1 py-1.5">
        <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-caption font-semibold text-primary-foreground">
          {definition.audience === 'PLATFORM' ? <Icon name="shield" size={14} /> : tenantCode.slice(0, 1).toUpperCase()}
        </span>
        {!collapsed && (
          <span className="flex min-w-0 flex-col">
            <span className="truncate text-body-strong">
              {definition.audience === 'PLATFORM' ? definition.title : tenantCode}
            </span>
            {definition.audience === 'TENANT' && (
              <span className="truncate text-caption text-muted-foreground">{globalThis.location?.hostname}</span>
            )}
          </span>
        )}
      </div>

      <nav aria-label={t('shell.remotes')} className="flex flex-col gap-0.5">
        {remotes.map((remote) => {
          const open = place.remote?.name === remote.name
          const sections = open ? (navigation[remote.name] ?? []) : []
          return (
            <div key={remote.name} className="flex flex-col gap-0.5">
              <Link
                to="/r/$remote/$"
                params={{ remote: remote.name, _splat: '' }}
                title={collapsed ? remote.title : undefined}
                className={cn(
                  'flex items-center gap-2 rounded-md px-2 py-1.5 text-body text-sidebar-foreground hover:bg-sidebar-accent',
                  open && 'text-foreground',
                  open && sections.length === 0 && 'bg-sidebar-accent',
                )}
              >
                <AppMark app={remote.appCode} size="sm" />
                {!collapsed && <span className="truncate font-medium">{remote.title}</span>}
              </Link>
              {!collapsed &&
                sections.map((section) => (
                  <div key={section.id} className="ml-3 flex flex-col gap-0.5 border-l border-border pl-2">
                    {section.label && (
                      <span className="px-2 pt-2 pb-0.5 text-caption text-muted-foreground">{section.label}</span>
                    )}
                    {section.items.map((item) => {
                      const active = place.item?.id === item.id
                      return (
                        <Link
                          key={item.id}
                          to="/r/$remote/$"
                          params={{ remote: remote.name, _splat: item.to.replace(/^\//, '') }}
                          aria-current={active ? 'page' : undefined}
                          className={cn(
                            'rounded-md px-2 py-1 text-small text-sidebar-foreground hover:bg-sidebar-accent hover:text-foreground',
                            active && 'bg-sidebar-accent text-foreground',
                          )}
                        >
                          {item.label}
                        </Link>
                      )
                    })}
                  </div>
                ))}
            </div>
          )
        })}
      </nav>

      <div className="mt-auto flex items-center gap-2 rounded-md px-2 py-1.5">
        <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-secondary text-caption">
          {me.displayName.slice(0, 1).toUpperCase()}
        </span>
        {!collapsed && (
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate text-small">{me.displayName}</span>
            <span className="truncate text-caption text-muted-foreground">{me.email}</span>
          </span>
        )}
        {onSignOut && !collapsed && (
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
      {collapsed && (
        <button
          type="button"
          onClick={onToggleSidebar}
          aria-label={t('shell.toggleSidebar')}
          className="rounded-md p-1.5 text-muted-foreground hover:bg-sidebar-accent hover:text-foreground"
        >
          <Icon name="panel-left" size={14} />
        </button>
      )}
    </aside>
  )
}
