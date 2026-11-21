import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'
import { usePresence } from './use-presence'

export interface BulkBarProps {
  count: number
  onClear: () => void
  children?: React.ReactNode
  className?: string
}

/** The floating bar of actions for the selected rows; it leaves when nothing is selected and renders nothing after. */
export function BulkBar({ count, onClear, children, className }: BulkBarProps) {
  const t = useUiMessages()
  const { shown } = usePresence(count > 0 ? count : null)
  if (shown === null) {
    return null
  }
  return (
    <section
      aria-label={t('table.selected', { count: shown })}
      data-state={count === 0 ? 'closed' : 'open'}
      className={cn(
        'data-[state=closed]:pointer-events-none data-[state=closed]:animate-fade-out',
        'fixed bottom-6 left-1/2 z-(--z-bar) flex -translate-x-1/2 animate-rise-in items-center gap-3 rounded-xl bg-primary px-3 py-2 text-primary-foreground shadow-lg',
        'max-md:fixed max-md:inset-x-3 max-md:bottom-[calc(0.75rem+env(safe-area-inset-bottom))] max-md:translate-x-0 max-md:justify-between',
        className,
      )}
    >
      <span className="text-body-strong">{t('table.selected', { count: shown })}</span>
      <span aria-hidden className="h-4 w-px bg-primary-foreground/20" />
      <div className="flex items-center gap-1">{children}</div>
      <button
        type="button"
        onClick={onClear}
        className="motion-colors flex size-8 items-center justify-center rounded-md text-primary-foreground/80 hover:bg-primary-foreground/10 hover:text-primary-foreground max-md:size-(--size-touch-target)"
        aria-label={t('action.clear')}
      >
        <Icon name="x" size={14} />
      </button>
    </section>
  )
}
