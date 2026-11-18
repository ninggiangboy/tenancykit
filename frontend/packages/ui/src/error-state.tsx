import { Button } from './button'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'

export interface ErrorStateProps {
  title?: React.ReactNode
  description?: React.ReactNode
  onRetry?: () => void
  forbidden?: boolean
  className?: string
}

/** A failed request says what failed, that nothing changed, and offers Retry. */
export function ErrorState({ title, description, onRetry, forbidden, className }: ErrorStateProps) {
  const t = useUiMessages()
  return (
    <div
      role="alert"
      className={cn('flex flex-col items-start gap-3 rounded-lg border border-border bg-card p-5', className)}
    >
      <div className="flex items-start gap-2.5">
        <Icon name={forbidden ? 'ban' : 'alert'} size={16} className="mt-0.5 text-destructive" />
        <div className="flex flex-col gap-0.5">
          <p className="text-body-strong text-foreground">{title ?? t('error.title')}</p>
          <p className="text-small text-muted-foreground">
            {description ?? (forbidden ? t('error.forbidden') : t('error.hint'))}
          </p>
        </div>
      </div>
      {onRetry && !forbidden && (
        <Button variant="outline" size="sm" icon="refresh" onClick={onRetry}>
          {t('action.retry')}
        </Button>
      )}
    </div>
  )
}
