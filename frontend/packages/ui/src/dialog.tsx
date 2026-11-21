import * as DialogPrimitive from '@radix-ui/react-dialog'
import { useEffect, useRef } from 'react'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'
import { useDragDismiss } from './use-drag-dismiss'
import { PHONE_QUERY, useMedia } from './use-media'

export interface DialogProps {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  description?: React.ReactNode
  children?: React.ReactNode
  footer?: React.ReactNode
  role?: 'dialog' | 'alertdialog'
  /** Renders inside its container instead of the viewport; a remote's dialog still stacks over the shell's sheet. */
  contained?: boolean
  /** `lg` for a form with two columns or a long list. */
  size?: 'md' | 'lg'
}

/**
 * A modal decision. The caller only opens it for something that cannot be undone, and the body is the impact list:
 * what stops, what is kept and who is affected.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  role = 'dialog',
  contained,
  size = 'md',
}: DialogProps) {
  const t = useUiMessages()
  const phone = useMedia(PHONE_QUERY)
  const panel = useRef<HTMLDivElement>(null)
  const scrim = useRef<HTMLDivElement>(null)
  const drag = useDragDismiss(panel, onClose, phone, scrim)
  // Radix restores focus to a Trigger; this dialog is opened by a caller's own button, so it remembers the element
  // that had focus when it opened and hands focus back to it on close.
  const opener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (open) {
      opener.current = document.activeElement as HTMLElement | null
    }
  }, [open])

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogPrimitive.Portal container={contained ? document.body : undefined}>
        <DialogPrimitive.Overlay
          ref={scrim}
          className="fixed inset-0 z-(--z-scrim) bg-overlay data-[state=closed]:animate-fade-out data-[state=open]:animate-fade-in"
        />
        <DialogPrimitive.Content
          ref={panel}
          role={role}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            opener.current?.focus()
          }}
          className={cn(
            phone
              ? 'data-[state=closed]:animate-slide-out-down data-[state=open]:animate-slide-in-up'
              : 'data-[state=closed]:animate-zoom-out data-[state=open]:animate-zoom-in',
            'fixed left-1/2 top-1/2 z-(--z-modal) flex max-h-[calc(100dvh-32px)] w-[calc(100%-32px)] -translate-x-1/2 -translate-y-1/2 flex-col rounded-xl border border-border bg-popover p-5 text-popover-foreground shadow-xl',
            size === 'lg' ? 'max-w-[640px]' : 'max-w-[520px]',
            'max-md:inset-x-0 max-md:bottom-0 max-md:top-auto max-md:left-0 max-md:w-full max-md:max-w-none max-md:translate-x-0 max-md:translate-y-0',
            'max-md:rounded-b-none max-md:rounded-t-[20px] max-md:max-h-[88dvh] max-md:pt-7 max-md:pb-[calc(1.25rem+env(safe-area-inset-bottom))]',
          )}
        >
          <span
            aria-hidden
            className="md:hidden absolute top-2 left-1/2 -ml-5 h-[5px] w-10 rounded-full bg-input touch-none"
            {...drag}
          />
          <div className="flex items-start justify-between gap-3" {...drag}>
            <div className="flex flex-col gap-1">
              <DialogPrimitive.Title className="text-heading text-foreground">{title}</DialogPrimitive.Title>
              {description && (
                <DialogPrimitive.Description className="text-small text-muted-foreground">
                  {description}
                </DialogPrimitive.Description>
              )}
            </div>
            <DialogPrimitive.Close
              aria-label={t('action.close')}
              className="motion-colors -mr-1.5 -mt-1.5 flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground max-md:size-(--size-touch-target)"
            >
              <Icon name="x" size={14} />
            </DialogPrimitive.Close>
          </div>
          {children && <div className="-mx-1 mt-4 min-h-0 overflow-auto px-1">{children}</div>}
          {footer && (
            <div className="mt-5 flex justify-end gap-2 max-md:flex-col-reverse max-md:[&_button]:h-11 max-md:[&_button]:w-full">
              {footer}
            </div>
          )}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

/** The impact list inside a confirmation: what stops, what is kept, who is affected. */
export function DialogImpact({ items }: { items: Array<{ label: React.ReactNode; value: React.ReactNode }> }) {
  return (
    <dl className="divide-y divide-border rounded-lg border border-border">
      {items.map((item) => (
        <div key={String(item.label)} className="flex items-center justify-between gap-3 px-3 py-2 text-small">
          <dt className="text-muted-foreground">{item.label}</dt>
          <dd className="text-body-strong text-foreground">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
