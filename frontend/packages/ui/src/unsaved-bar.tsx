import { Button } from './button'
import { cn } from './cn'
import { useUiMessages } from './messages'

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
  if (count === 0) {
    return null
  }
  return (
    <section
      className={cn(
        'fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 animate-rise-in items-center gap-3 rounded-xl border border-border bg-popover px-3 py-2 shadow-lg',
        className,
      )}
    >
      <span className="text-body-strong text-foreground">{t('unsaved.count', { count })}</span>
      <Button variant="ghost" size="sm" onClick={onDiscard} disabled={saving}>
        {t('action.discard')}
      </Button>
      <Button size="sm" loading={saving} onClick={onSave} shortcut={['mod', 's']}>
        {t('action.save')}
      </Button>
    </section>
  )
}
