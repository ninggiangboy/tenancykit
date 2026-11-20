import { Sheet } from '@suite/ui'
import type { ReactNode } from 'react'

export interface RecordSheetProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  eyebrow?: ReactNode
  description?: ReactNode
  footer?: ReactNode
  actions?: ReactNode
  figure?: boolean
  placement?: 'auto' | 'side' | 'bottom'
  onPrev?: () => void
  onNext?: () => void
  children: ReactNode
}

/**
 * A record beside its list. A click on the scrim closes it, but a click inside a child dialog opened from the sheet
 * does not: that dialog is the surface the person is working on.
 */
export function RecordSheet({ open, onClose, ...props }: RecordSheetProps) {
  return (
    <Sheet
      open={open}
      onClose={onClose}
      onInteractOutside={(event) => {
        const target = event.target as Element | null
        if (target?.closest('[role="dialog"], [role="alertdialog"], [data-radix-popper-content-wrapper]')) {
          event.preventDefault()
        }
      }}
      {...props}
    />
  )
}
