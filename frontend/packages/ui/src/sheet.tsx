import * as DialogPrimitive from '@radix-ui/react-dialog'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'

export interface SheetProps {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  eyebrow?: React.ReactNode
  description?: React.ReactNode
  children?: React.ReactNode
  footer?: React.ReactNode
  onPrev?: () => void
  onNext?: () => void
  modal?: boolean
  contained?: boolean
  /** A record sheet keeps its place while a child dialog is open; a caller can hold it against outside interaction. */
  onInteractOutside?: React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content>['onInteractOutside']
}

/**
 * Peek, do not navigate: the record opens beside the list, keeps its own URL, and steps to the previous and next record
 * without losing the filters underneath.
 */
export function Sheet({
  open,
  onClose,
  title,
  eyebrow,
  description,
  children,
  footer,
  onPrev,
  onNext,
  modal = false,
  contained,
  onInteractOutside,
}: SheetProps) {
  const t = useUiMessages()
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => (next ? undefined : onClose())} modal={modal}>
      <DialogPrimitive.Portal container={contained ? document.body : undefined}>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-overlay/40 data-[state=open]:animate-in" />
        <DialogPrimitive.Content
          onInteractOutside={onInteractOutside}
          className={cn(
            'fixed inset-y-2 right-2 z-50 flex w-full max-w-[420px] flex-col rounded-xl border border-border bg-popover text-popover-foreground shadow-lg',
          )}
        >
          <header className="flex flex-col gap-1 border-b border-border p-4">
            <div className="flex items-center justify-between gap-2">
              {eyebrow ? <span className="text-caption text-muted-foreground">{eyebrow}</span> : <span />}
              <div className="flex items-center gap-1">
                {onPrev && (
                  <button
                    type="button"
                    onClick={onPrev}
                    aria-label={t('action.previous')}
                    className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                  >
                    <Icon name="chevron-left" size={14} />
                  </button>
                )}
                {onNext && (
                  <button
                    type="button"
                    onClick={onNext}
                    aria-label={t('action.next')}
                    className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                  >
                    <Icon name="chevron-right" size={14} />
                  </button>
                )}
                <DialogPrimitive.Close
                  aria-label={t('action.close')}
                  className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                >
                  <Icon name="x" size={14} />
                </DialogPrimitive.Close>
              </div>
            </div>
            <DialogPrimitive.Title className="text-heading text-foreground">{title}</DialogPrimitive.Title>
            {description && (
              <DialogPrimitive.Description className="text-small text-muted-foreground">
                {description}
              </DialogPrimitive.Description>
            )}
          </header>
          <div className="flex-1 overflow-auto p-4">{children}</div>
          {footer && <footer className="flex justify-end gap-2 border-t border-border p-3">{footer}</footer>}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
