import * as DialogPrimitive from '@radix-ui/react-dialog'
import { useRef } from 'react'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'
import { useDragDismiss } from './use-drag-dismiss'
import { PHONE_QUERY, useMedia } from './use-media'

export interface SheetProps {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  eyebrow?: React.ReactNode
  description?: React.ReactNode
  children?: React.ReactNode
  footer?: React.ReactNode
  /** The primary action, up to one outline action, then a "…" menu; docked at the bottom on phones. */
  actions?: React.ReactNode
  /** The title is the record's key figure, such as the amount. */
  figure?: boolean
  /** `auto` is a side sheet from 768px up and a bottom sheet below it. */
  placement?: 'auto' | 'side' | 'bottom'
  onPrev?: () => void
  onNext?: () => void
  modal?: boolean
  contained?: boolean
  /** A record sheet keeps its place while a child dialog is open; a caller can hold it against outside interaction. */
  onInteractOutside?: React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content>['onInteractOutside']
}

/**
 * Peek, do not navigate: the record opens beside the list, keeps its own URL, and steps to the previous and next record
 * without losing the filters underneath. On phones it rises from the bottom and follows a drag down.
 */
export function Sheet({
  open,
  onClose,
  title,
  eyebrow,
  description,
  children,
  footer,
  actions,
  figure,
  placement = 'auto',
  onPrev,
  onNext,
  modal = true,
  contained,
  onInteractOutside,
}: SheetProps) {
  const t = useUiMessages()
  const phone = useMedia(PHONE_QUERY)
  const panel = useRef<HTMLDivElement>(null)
  const bottom = placement === 'bottom' || (placement === 'auto' && phone)
  const scrim = useRef<HTMLDivElement>(null)
  const drag = useDragDismiss(panel, onClose, bottom, scrim)
  const iconButton = cn(
    'motion-colors flex items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground',
    bottom ? 'size-11' : 'size-8',
  )

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => (next ? undefined : onClose())} modal={modal}>
      <DialogPrimitive.Portal container={contained ? document.body : undefined}>
        {modal && (
          <DialogPrimitive.Overlay
            ref={scrim}
            className="fixed inset-0 z-40 bg-overlay backdrop-blur-[2px] data-[state=closed]:animate-fade-out data-[state=open]:animate-fade-in"
          />
        )}
        <DialogPrimitive.Content
          ref={panel}
          onInteractOutside={onInteractOutside}
          // Focus lands on the panel itself, not on the first button (the "next record" arrow), so a keyboard user
          // starts at the top of the record and Esc still closes it.
          onOpenAutoFocus={(event) => {
            event.preventDefault()
            ;(event.currentTarget as HTMLElement | null)?.focus()
          }}
          tabIndex={-1}
          className={cn(
            'flex flex-col bg-popover text-popover-foreground outline-none',
            bottom
              ? 'fixed inset-x-0 bottom-0 z-50 h-[88dvh] rounded-t-[20px] pb-[env(safe-area-inset-bottom)] shadow-xl data-[state=closed]:animate-slide-out-down data-[state=open]:animate-slide-in-up'
              : 'fixed inset-y-2 right-2 z-50 w-[calc(100%-16px)] max-w-[460px] rounded-xl border border-border shadow-lg data-[state=closed]:animate-slide-out-right data-[state=open]:animate-slide-in-right',
          )}
        >
          {bottom && (
            <span
              aria-hidden
              className="mx-auto mt-2 h-[5px] w-10 shrink-0 touch-none rounded-full bg-input"
              {...drag}
            />
          )}
          <header className={cn('flex flex-col gap-1 border-b border-border p-4', bottom && 'touch-none')} {...drag}>
            <div className="flex items-center justify-between gap-2">
              {eyebrow ? <span className="text-small text-muted-foreground">{eyebrow}</span> : <span />}
              <div className="flex items-center gap-1">
                {!bottom && onPrev && (
                  <button type="button" onClick={onPrev} aria-label={t('action.previous')} className={iconButton}>
                    <Icon name="chevron-left" size={14} />
                  </button>
                )}
                {!bottom && onNext && (
                  <button type="button" onClick={onNext} aria-label={t('action.next')} className={iconButton}>
                    <Icon name="chevron-right" size={14} />
                  </button>
                )}
                <DialogPrimitive.Close aria-label={t('action.close')} className={iconButton}>
                  <Icon name="x" size={14} />
                </DialogPrimitive.Close>
              </div>
            </div>
            <DialogPrimitive.Title className={cn('text-heading text-foreground', figure && 'text-metric tabular-nums')}>
              {title}
            </DialogPrimitive.Title>
            {description && (
              <DialogPrimitive.Description className="flex flex-wrap items-center gap-2 text-small text-muted-foreground">
                {description}
              </DialogPrimitive.Description>
            )}
            {actions && !bottom && <div className="mt-2 flex flex-wrap gap-2">{actions}</div>}
          </header>
          <div className={cn('flex flex-1 flex-col gap-6 overflow-y-auto overscroll-contain', bottom ? 'p-4' : 'p-5')}>
            {children}
          </div>
          {actions && bottom && (
            <div className="flex gap-2 border-t border-border bg-popover px-4 py-3 [&_button]:h-11 [&_button]:flex-1 [&_button]:text-[15px] [&_button[aria-label]]:w-11 [&_button[aria-label]]:flex-none">
              {actions}
            </div>
          )}
          {footer && <footer className="flex justify-end gap-2 border-t border-border p-3">{footer}</footer>}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
