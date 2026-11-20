import { DropdownMenu, Icon, Kbd } from '@suite/ui'
import { Link, useLocation } from '@tanstack/react-router'
import { currentPlace, useFrame } from './frame-context'

/** The breadcrumb (shell, app, screen), the scope pill, search and the account menus. */
export function Header() {
  const frame = useFrame()
  const { definition, me, onToggleSidebar, onOpenPalette, onTheme, locale, onLocale, onSignOut, t } = frame
  const pathname = useLocation({ select: (location) => location.pathname })
  const place = currentPlace(pathname, frame)

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
      <nav aria-label="Breadcrumb" className="flex min-w-0 items-center gap-1 text-small">
        {place.remote ? (
          <>
            <Link
              to="/r/$remote/$"
              params={{ remote: place.remote.name, _splat: '' }}
              className={
                place.item ? 'truncate text-muted-foreground hover:text-foreground' : 'truncate text-body-strong'
              }
            >
              {place.remote.title}
            </Link>
            {place.item && (
              <>
                <Icon name="chevron-right" size={12} className="shrink-0 text-muted-foreground" />
                <span className="truncate text-body-strong" aria-current="page">
                  {place.item.label}
                </span>
              </>
            )}
          </>
        ) : (
          <span className="text-body-strong">{t('shell.home')}</span>
        )}
      </nav>
      {definition.audience === 'PLATFORM' && (
        <span className="ml-1 inline-flex h-5 shrink-0 items-center gap-1.5 rounded-full border border-border px-2 text-caption text-muted-foreground max-md:hidden">
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
          {/* `max-sm:hidden`, not `hidden sm:inline`: a remote's stylesheet loads later and its own `.hidden` would win. */}
          <span className="w-36 text-left max-sm:hidden">{t('shell.search')}</span>
          <Kbd keys={['mod', 'k']} className="max-sm:hidden" />
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
