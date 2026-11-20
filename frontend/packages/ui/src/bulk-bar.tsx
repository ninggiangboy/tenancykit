import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'

export interface BulkBarProps {
  count: number
  onClear: () => void
  children?: React.ReactNode
  className?: string
}

/** The floating bar of actions for the selected rows; it renders nothing when nothing is selected. */
export function BulkBar({ count, onClear, children, className }: BulkBarProps) {
  const t = useUiMessages()
  if (count === 0) {
    return null
  }
  return (
    <section
      aria-label={t('table.selected', { count })}
      className={cn(
        'fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-xl bg-primary px-3 py-2 text-primary-foreground shadow-lg',
        'max-md:fixed max-md:inset-x-3 max-md:bottom-[calc(0.75rem+env(safe-area-inset-bottom))] max-md:translate-x-0 max-md:justify-between',
        className,
      )}
    >
      <span className="text-body-strong">{t('table.selected', { count })}</span>
      <span aria-hidden className="h-4 w-px bg-primary-foreground/20" />
      <div className="flex items-center gap-1">{children}</div>
      <button
        type="button"
        onClick={onClear}
        className="rounded-md p-1 text-primary-foreground/80 hover:bg-primary-foreground/10 hover:text-primary-foreground"
        aria-label={t('action.clear')}
      >
        <Icon name="x" size={14} />
      </button>
    </section>
  )
}
