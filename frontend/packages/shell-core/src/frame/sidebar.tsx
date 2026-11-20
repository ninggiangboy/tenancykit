import { AppMark, cn, Icon, type IconName, PHONE_QUERY, TABLET_QUERY, Tooltip, useMedia } from '@suite/ui'
import { Link, useLocation } from '@tanstack/react-router'
import { useEffect } from 'react'
import type { ConsoleRemote } from '../contracts'
import { currentPlace, useFrame } from './frame-context'

/** The open app once, its screens beneath it, and the other apps at the foot. A rail on tablets, a drawer on phones. */
export function Sidebar() {
  const frame = useFrame()
  const { definition, me, remotes, navigation, collapsed, drawerOpen, onToggleDrawer, onSignOut, t } = frame
  const pathname = useLocation({ select: (location) => location.pathname })
  const place = currentPlace(pathname, frame)
  const phone = useMedia(PHONE_QUERY)
  const tablet = useMedia(TABLET_QUERY)
  const rail = !phone && (tablet || collapsed)
  const tenantCode = globalThis.location?.hostname.split('.')[0] ?? ''
  const openRemote = place.remote
  const otherRemotes = remotes.filter((remote) => remote.name !== openRemote?.name)
  const sections = openRemote ? (navigation[openRemote.name] ?? []) : []

  // Choosing a screen closes the phone's drawer.
  // biome-ignore lint/correctness/useExhaustiveDependencies: only a new path closes the drawer.
  useEffect(() => {
    if (phone && drawerOpen) onToggleDrawer()
  }, [pathname])

  useEffect(() => {
    if (!drawerOpen) return
    const listener = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') onToggleDrawer()
    }
    document.addEventListener('keydown', listener)
    return () => document.removeEventListener('keydown', listener)
  }, [drawerOpen, onToggleDrawer])

  const itemClass = (active: boolean) =>
    cn(
      'flex h-9 items-center gap-2.5 rounded-md px-2.5 text-body text-sidebar-foreground hover:bg-sidebar-accent',
      'max-md:h-(--size-touch-target) max-md:text-[15px]',
      active && 'bg-app-accent-soft font-semibold text-primary',
      rail && 'justify-center px-0',
    )

  const appLink = (remote: ConsoleRemote) => {
    const active = openRemote?.name === remote.name
    const link = (
      <Link
        to="/r/$remote/$"
        params={{ remote: remote.name, _splat: '' }}
        aria-current={active ? 'page' : undefined}
        className={itemClass(active)}
      >
        <AppMark app={remote.appCode} size="sm" />
        {!rail && <span className="truncate font-semibold">{remote.title}</span>}
      </Link>
    )
    return <li key={remote.name}>{rail ? <Tooltip content={remote.title}>{link}</Tooltip> : link}</li>
  }

  return (
    <>
      {phone && drawerOpen && (
        // biome-ignore lint/a11y/noStaticElementInteractions: the scrim is the drawer's backdrop.
        // biome-ignore lint/a11y/useKeyWithClickEvents: Escape closes the drawer from anywhere.
        <div className="fixed inset-0 z-40 bg-overlay" onClick={onToggleDrawer} />
      )}
      <aside
        className={cn(
          'flex shrink-0 flex-col gap-3 overflow-y-auto',
          phone
            ? cn(
                'fixed inset-y-0 left-0 z-50 w-(--size-drawer-width) bg-sidebar p-3 shadow-xl transition-transform duration-[260ms]',
                drawerOpen ? 'translate-x-0' : '-translate-x-full',
              )
            : cn('sticky top-0 h-dvh', rail ? 'w-(--size-sidebar-width-icon) p-1.5' : 'w-60 p-2'),
        )}
      >
        <div className={cn('flex items-center gap-2 px-1 py-1.5', rail && 'justify-center px-0')}>
          <span className="flex size-[28px] shrink-0 items-center justify-center rounded-md bg-foreground text-caption font-semibold text-background">
            {definition.audience === 'PLATFORM' ? (
              <Icon name="shield" size={14} />
            ) : (
              tenantCode.slice(0, 1).toUpperCase()
            )}
          </span>
          {!rail && (
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
          {openRemote ? (
            <>
              <ul className="flex flex-col gap-0.5">{appLink(openRemote)}</ul>
              {sections.map((section) => (
                <div key={section.id} className="flex flex-col gap-0.5">
                  {section.label && !rail && (
                    <span className="px-2.5 pt-3 pb-1 text-caption text-muted-foreground">{section.label}</span>
                  )}
                  {section.items.map((item) => {
                    const active = place.item?.id === item.id
                    const link = (
                      <Link
                        key={item.id}
                        to="/r/$remote/$"
                        params={{ remote: openRemote.name, _splat: item.to.replace(/^\//, '') }}
                        aria-current={active ? 'page' : undefined}
                        className={itemClass(active)}
                      >
                        <Icon
                          name={(item.icon as IconName | undefined) ?? 'file'}
                          size={16}
                          className={cn('text-muted-foreground', active && 'text-primary')}
                        />
                        {!rail && <span className="truncate">{item.label}</span>}
                      </Link>
                    )
                    return rail ? (
                      <Tooltip key={item.id} content={item.label}>
                        {link}
                      </Tooltip>
                    ) : (
                      link
                    )
                  })}
                </div>
              ))}
            </>
          ) : (
            <>
              {!rail && (
                <span className="px-2.5 pt-3 pb-1 text-caption text-muted-foreground">{t('shell.remotes')}</span>
              )}
              <ul className="flex flex-col gap-0.5">{remotes.map(appLink)}</ul>
            </>
          )}
        </nav>

        {openRemote && otherRemotes.length > 0 && (
          <div className="mt-auto flex flex-col gap-0.5 border-t border-sidebar-border pt-2">
            {!rail && <span className="px-2.5 pb-1 text-caption text-muted-foreground">{t('shell.otherApps')}</span>}
            <ul className="flex flex-col gap-0.5">{otherRemotes.map(appLink)}</ul>
          </div>
        )}

        <div className={cn('mt-auto flex items-center gap-2 rounded-md px-2 py-1.5', rail && 'justify-center px-0')}>
          <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-secondary text-caption">
            {me.displayName.slice(0, 1).toUpperCase()}
          </span>
          {!rail && (
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-small">{me.displayName}</span>
              <span className="truncate text-caption text-muted-foreground">{me.email}</span>
            </span>
          )}
          {onSignOut && !rail && (
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
        {rail && (
          <button
            type="button"
            onClick={frame.onToggleSidebar}
            aria-label={t('shell.toggleSidebar')}
            className="self-center rounded-md p-1.5 text-muted-foreground hover:bg-sidebar-accent hover:text-foreground"
          >
            <Icon name="panel-left" size={14} />
          </button>
        )}
      </aside>
    </>
  )
}
