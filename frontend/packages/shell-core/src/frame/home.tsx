import { AppMark, EmptyState } from '@suite/ui'
import { Link } from '@tanstack/react-router'
import { appEntry } from './app-catalog'
import { useFrame } from './frame-context'

/** One card per app the person may open, in that app's accent, with its purpose and its common screens. */
export function Home() {
  const { definition, me, remotes, t } = useFrame()
  if (remotes.length === 0) {
    return (
      <EmptyState
        icon="grid"
        title={definition.audience === 'TENANT' ? t('shell.noApps') : t('shell.home')}
        description={t('shell.noAppsHint')}
      />
    )
  }
  const tenantCode = globalThis.location?.hostname.split('.')[0] ?? ''
  const scopeName = definition.audience === 'PLATFORM' ? definition.title : tenantCode
  return (
    <div className="flex flex-col gap-6 px-(--page-pad) py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-title text-foreground max-md:text-[22px] max-md:leading-7">
          {t('shell.greeting', { name: me.displayName })}
        </h1>
        <p className="text-small text-muted-foreground">
          {scopeName} · {t('shell.appsEnabled', { count: remotes.length })}
        </p>
      </header>
      <ul className="grid gap-4 [grid-template-columns:repeat(auto-fit,minmax(280px,1fr))]">
        {remotes.map((remote) => {
          const entry = appEntry(remote.appCode, definition.audience)
          return (
            <li
              key={remote.name}
              data-app={remote.appCode}
              className="motion-colors flex flex-col gap-3 rounded-lg bg-card p-5 shadow-[0_0_0_1px_var(--border)] hover:shadow-[0_0_0_1px_var(--app-accent)]"
            >
              <Link
                to="/r/$remote/$"
                params={{ remote: remote.name, _splat: '' }}
                className="flex min-h-(--size-touch-target) items-center gap-3"
              >
                <AppMark app={remote.appCode} size="lg" />
                <span className="flex min-w-0 flex-col">
                  <span className="truncate text-[15px] font-semibold">{remote.title}</span>
                  {entry && <span className="truncate text-small text-muted-foreground">{t(entry.purpose)}</span>}
                </span>
              </Link>
              {entry && <p className="text-[13.5px] text-muted-foreground">{t(entry.description)}</p>}
              {entry && (
                <div className="flex flex-wrap gap-1.5">
                  {entry.shortcuts.map((shortcut) => (
                    <Link
                      key={shortcut.path}
                      to="/r/$remote/$"
                      params={{ remote: remote.name, _splat: shortcut.path }}
                      className="motion-colors inline-flex min-h-8 items-center rounded-md bg-app-accent-soft px-2.5 text-small font-medium text-app-accent hover:shadow-[inset_0_0_0_1px_var(--app-accent)] max-md:min-h-(--size-touch-target) max-md:px-3.5"
                    >
                      {t(shortcut.label)}
                    </Link>
                  ))}
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export function NotFound() {
  const { t } = useFrame()
  return <EmptyState icon="file" title={t('shell.notFound')} description={t('shell.notFoundHint')} />
}
