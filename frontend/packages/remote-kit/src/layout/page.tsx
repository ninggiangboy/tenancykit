import { cn, ErrorState } from '@suite/ui'
import type { ReactNode } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'

export interface PageHeaderProps {
  title: ReactNode
  description?: ReactNode
  /** The page's actions, primary last. */
  actions?: ReactNode
  /** The one main action: a `Button` with the default variant. A floating button on phones. */
  primaryAction?: ReactNode
}

export function PageHeader({ title, description, actions, primaryAction }: PageHeaderProps) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-3 px-(--page-pad) pt-5 pb-4">
      <div className="flex min-w-0 flex-col gap-1">
        <h1 className="text-title text-foreground max-md:text-[22px] max-md:leading-7">{title}</h1>
        {description && <p className="max-w-2xl text-small text-muted-foreground">{description}</p>}
      </div>
      {(actions || primaryAction) && (
        <div className="flex flex-wrap items-center gap-2">
          {actions}
          {primaryAction && <span className="max-md:hidden">{primaryAction}</span>}
        </div>
      )}
    </header>
  )
}

/** A screen: its header and a padded body. */
export function Page({ children, primaryAction, ...header }: PageHeaderProps & { children: ReactNode }) {
  return (
    <div className="flex flex-col">
      <PageHeader {...header} primaryAction={primaryAction} />
      <div className={cn('flex flex-col gap-(--page-gap) px-(--page-pad) pb-8', primaryAction && 'max-md:pb-24')}>
        {children}
      </div>
      {primaryAction && (
        <div className="fixed right-4 bottom-[calc(16px+env(safe-area-inset-bottom))] z-20 animate-rise-in md:hidden [&_button]:h-13 [&_button]:rounded-2xl [&_button]:px-5 [&_button]:text-[15px] [&_button]:shadow-lg">
          {primaryAction}
        </div>
      )}
    </div>
  )
}

/** A titled block inside a page or a sheet. */
export function Section({
  title,
  description,
  actions,
  children,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="flex flex-col gap-2">
      <div className="flex items-end justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-heading text-foreground">{title}</h2>
          {description && <p className="text-small text-muted-foreground">{description}</p>}
        </div>
        {actions && <div className="flex items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  )
}

/** For a page the caller may not read: the backend would answer 403, so the screen says so before asking. */
export function Forbidden({ bare, permission }: { bare?: boolean; permission?: string }) {
  const t = useKitT()
  const description = permission ? t('state.forbiddenHint', { permission }) : t('state.forbiddenHintGeneric')
  const state = <ErrorState forbidden title={t('state.forbidden')} description={description} />
  return bare ? state : <div className="p-6">{state}</div>
}
