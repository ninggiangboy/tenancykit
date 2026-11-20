import { AppMark, EmptyState, Icon } from '@suite/ui'
import { Link } from '@tanstack/react-router'
import { useFrame } from './frame-context'

/** The apps this person may open, as cards; a tenant with none is told who can enable one. */
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
  return (
    <div className="flex flex-col gap-6 px-6 py-8">
      <header className="flex flex-col gap-1">
        <h1 className="text-title text-foreground">{me.displayName}</h1>
        <p className="text-small text-muted-foreground">{t('shell.welcome')}</p>
      </header>
      <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {remotes.map((remote) => (
          <li key={remote.name}>
            <Link
              to="/r/$remote/$"
              params={{ remote: remote.name, _splat: '' }}
              className="group flex items-center gap-3 rounded-xl border border-border bg-card p-4 shadow-xs transition-colors hover:bg-accent"
            >
              <AppMark app={remote.appCode} />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-body-strong text-foreground">{remote.title}</span>
                <span className="truncate font-mono text-caption text-muted-foreground">{remote.appCode}</span>
              </span>
              <span className="flex items-center gap-1 text-small text-muted-foreground group-hover:text-foreground">
                {t('shell.open')}
                <Icon name="arrow-up-right" size={14} />
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function NotFound() {
  const { t } = useFrame()
  return <EmptyState icon="file" title={t('shell.notFound')} description={t('shell.notFoundHint')} />
}
