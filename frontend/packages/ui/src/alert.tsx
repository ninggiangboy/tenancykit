import { cn } from './cn'
import { Icon, type IconName } from './icon'

export type AlertTone = 'info' | 'success' | 'warning' | 'danger'

const tone: Record<AlertTone, { icon: IconName; className: string }> = {
  info: { icon: 'info', className: 'text-info' },
  success: { icon: 'check-circle', className: 'text-success' },
  warning: { icon: 'alert', className: 'text-warning' },
  danger: { icon: 'x-circle', className: 'text-destructive' },
}

export interface AlertProps {
  tone?: AlertTone
  title?: React.ReactNode
  children?: React.ReactNode
  action?: React.ReactNode
  className?: string
}

/** A persistent fact about the view it sits in: what will happen, or what is about to stop. */
export function Alert({ tone: alertTone = 'info', title, children, action, className }: AlertProps) {
  const { icon, className: iconClass } = tone[alertTone]
  return (
    <div
      role="status"
      className={cn('flex items-start gap-2.5 rounded-lg border border-border bg-card p-3', className)}
    >
      <Icon name={icon} size={16} className={cn('mt-0.5', iconClass)} />
      <div className="flex flex-1 flex-col gap-0.5">
        {title && <p className="text-body-strong text-foreground">{title}</p>}
        {children && <p className="text-small text-muted-foreground">{children}</p>}
      </div>
      {action}
    </div>
  )
}
