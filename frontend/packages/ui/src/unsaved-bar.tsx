import { Button } from './button'
import { cn } from './cn'
import { useUiMessages } from './messages'
import { usePresence } from './use-presence'

export interface UnsavedBarProps {
  count: number
  saving?: boolean
  onDiscard: () => void
  onSave: () => void
  className?: string
}

/** The floating bar a form with many edits shows instead of a Save button somewhere out of sight. */
export function UnsavedBar({ count, saving, onDiscard, onSave, className }: UnsavedBarProps) {
  const t = useUiMessages()
  const { shown } = usePresence(count > 0 ? count : null)
  if (shown === null) {
    return null
  }
  return (
    <section
      data-state={count === 0 ? 'closed' : 'open'}
      className={cn(
        'data-[state=closed]:pointer-events-none data-[state=closed]:animate-fade-out',
        'fixed bottom-6 left-1/2 z-(--z-bar) flex -translate-x-1/2 animate-rise-in items-center gap-3 rounded-xl border border-border bg-popover px-3 py-2 shadow-lg',
        className,
      )}
    >
      <span className="text-body-strong text-foreground">{t('unsaved.count', { count: shown })}</span>
      <Button variant="ghost" size="sm" onClick={onDiscard} disabled={saving}>
        {t('action.discard')}
      </Button>
      <Button size="sm" loading={saving} onClick={onSave} shortcut={['mod', 's']}>
        {t('action.save')}
      </Button>
    </section>
  )
}
