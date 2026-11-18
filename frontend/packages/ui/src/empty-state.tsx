import { cn } from './cn'
import { Icon, type IconName } from './icon'

export interface EmptyStateProps {
  icon?: IconName
  title: React.ReactNode
  description?: React.ReactNode
  children?: React.ReactNode
  className?: string
}

/** Never a dead end: it says why the view is empty and what fills it. */
export function EmptyState({ icon = 'inbox', title, description, children, className }: EmptyStateProps) {
  return (
    <div className={cn('flex flex-col items-center justify-center gap-3 px-6 py-10 text-center', className)}>
      <span className="flex size-10 items-center justify-center rounded-lg border border-border bg-muted text-muted-foreground">
        <Icon name={icon} size={18} />
      </span>
      <div className="flex flex-col gap-1">
        <p className="text-body-strong text-foreground">{title}</p>
        {description && <p className="max-w-sm text-small text-muted-foreground">{description}</p>}
      </div>
      {children}
    </div>
  )
}
