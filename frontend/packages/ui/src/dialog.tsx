import * as DialogPrimitive from '@radix-ui/react-dialog'
import { useEffect, useRef } from 'react'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'

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
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-overlay data-[state=open]:animate-in" />
        <DialogPrimitive.Content
          role={role}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            opener.current?.focus()
          }}
          className={cn(
            'fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col rounded-xl border border-border bg-popover p-5 text-popover-foreground shadow-xl',
            size === 'lg' ? 'max-w-2xl' : 'max-w-md',
          )}
        >
          <div className="flex items-start justify-between gap-3">
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
              className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <Icon name="x" size={14} />
            </DialogPrimitive.Close>
          </div>
          {children && <div className="-mx-1 mt-4 min-h-0 overflow-auto px-1">{children}</div>}
          {footer && <div className="mt-5 flex justify-end gap-2">{footer}</div>}
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
