import { AppMark, DropdownMenu, Icon, Kbd, type MenuItem, PHONE_QUERY, useMedia } from '@suite/ui'
import { Link, useLocation } from '@tanstack/react-router'
import { currentPlace, useFrame } from './frame-context'

/** The icon of the theme the person chose; System shows the monitor, not the look it resolves to. */
const THEME_ICON = { light: 'sun', dark: 'moon', system: 'monitor' } as const

/** The breadcrumb (shell, app, screen), search and the account menus; on phones a 56px top bar with a nav button. */
export function Header() {
  const frame = useFrame()
  const {
    definition,
    me,
    density,
    theme,
    onToggleSidebar,
    onToggleDrawer,
    onOpenPalette,
    onTheme,
    onDensity,
    locale,
    onLocale,
    onSignOut,
    t,
  } = frame
  const pathname = useLocation({ select: (location) => location.pathname })
  const place = currentPlace(pathname, frame)
  const phone = useMedia(PHONE_QUERY)
  const pageLabel = place.item?.label ?? place.remote?.title ?? t('shell.home')

  const accountItems: MenuItem[] = [
    { heading: me.email },
    { heading: t('shell.density') },
    { label: t('shell.comfortable'), checked: density === 'comfortable', onSelect: () => onDensity('comfortable') },
    { label: t('shell.compact'), checked: density === 'compact', onSelect: () => onDensity('compact') },
    { separator: true },
    ...(phone
      ? ([
          { heading: t('shell.language') },
          { label: t('shell.english'), checked: locale === 'en', onSelect: () => onLocale('en') },
          { label: t('shell.vietnamese'), checked: locale === 'vi', onSelect: () => onLocale('vi') },
        ] satisfies MenuItem[])
      : []),
    ...(onSignOut ? ([{ label: t('shell.signOut'), icon: 'log-out', onSelect: onSignOut }] satisfies MenuItem[]) : []),
  ]

  return (
    <div className="flex h-13 items-center gap-2 px-2 max-md:sticky max-md:top-0 max-md:z-(--z-sticky) max-md:h-[56px] max-md:border-b max-md:border-border max-md:bg-background">
      <button
        type="button"
        onClick={onToggleDrawer}
        aria-label={t('shell.openNavigation')}
        className="motion-colors flex size-(--size-touch-target) items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground md:hidden"
      >
        <Icon name="panel-left" size={18} />
      </button>
      <button
        type="button"
        onClick={onToggleSidebar}
        aria-label={t('shell.toggleSidebar')}
        className="motion-colors flex size-(--size-control-sm) items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground max-lg:hidden"
      >
        <Icon name="panel-left" size={14} />
      </button>
      <span className="flex min-w-0 flex-1 items-center gap-2 md:hidden">
        {place.remote && <AppMark app={place.remote.appCode} size="sm" />}
        <span className="truncate text-[17px] font-semibold">{pageLabel}</span>
      </span>
      <nav aria-label={t('shell.breadcrumb')} className="flex min-w-0 items-center gap-1 text-small max-md:hidden">
        {place.remote ? (
          <>
            <Link
              to="/r/$remote/$"
              params={{ remote: place.remote.name, _splat: '' }}
              className={
                place.item
                  ? 'motion-colors -mx-1.5 inline-flex h-7 items-center truncate rounded-md px-1.5 text-muted-foreground hover:text-foreground'
                  : 'inline-flex h-7 items-center truncate text-body-strong'
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
          className="motion-colors inline-flex h-(--size-control-sm) items-center gap-2 rounded-md border border-input bg-background px-2 text-small text-muted-foreground shadow-xs hover:bg-accent max-md:hidden"
        >
          <Icon name="search" size={14} />
          {/* `max-sm:hidden`, not `hidden sm:inline`: a remote's stylesheet loads later and its own `.hidden` would win. */}
          <span className="w-36 text-left max-sm:hidden">{t('shell.search')}</span>
          <Kbd keys={['mod', 'k']} className="max-sm:hidden" />
        </button>
        <button
          type="button"
          onClick={onOpenPalette}
          aria-label={t('shell.search')}
          className="motion-colors flex size-(--size-touch-target) items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground md:hidden"
        >
          <Icon name="search" size={18} />
        </button>
        <div className="max-md:hidden">
          <DropdownMenu
            label={t('shell.theme')}
            items={[
              { label: t('shell.light'), icon: 'sun', checked: theme === 'light', onSelect: () => onTheme('light') },
              { label: t('shell.dark'), icon: 'moon', checked: theme === 'dark', onSelect: () => onTheme('dark') },
              {
                label: t('shell.system'),
                icon: 'monitor',
                checked: theme === 'system',
                onSelect: () => onTheme('system'),
              },
            ]}
            trigger={
              <button
                type="button"
                aria-label={t('shell.theme')}
                className="motion-colors flex size-(--size-control-sm) items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <Icon name={THEME_ICON[theme]} size={14} />
              </button>
            }
          />
        </div>
        <div className="max-md:hidden">
          <DropdownMenu
            label={t('shell.language')}
            items={[
              { label: t('shell.english'), checked: locale === 'en', onSelect: () => onLocale('en') },
              { label: t('shell.vietnamese'), checked: locale === 'vi', onSelect: () => onLocale('vi') },
            ]}
            trigger={
              <button
                type="button"
                aria-label={`${t('shell.language')}: ${locale.toUpperCase()}`}
                className="motion-colors flex h-(--size-control-sm) min-w-8 items-center justify-center rounded-md px-1.5 text-caption text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                {locale.toUpperCase()}
              </button>
            }
          />
        </div>
        <DropdownMenu
          label={t('shell.user')}
          items={accountItems}
          trigger={
            <button
              type="button"
              aria-label={t('shell.user')}
              className="motion-colors flex size-(--size-control-sm) items-center justify-center rounded-full hover:bg-accent max-md:size-(--size-touch-target)"
            >
              <span className="flex size-7 items-center justify-center rounded-full bg-secondary text-caption">
                {me.displayName.slice(0, 1).toUpperCase()}
              </span>
            </button>
          }
        />
      </div>
    </div>
  )
}
