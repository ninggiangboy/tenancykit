import { Sheet } from '@suite/ui'
import type { ReactNode } from 'react'

export interface RecordSheetProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  eyebrow?: ReactNode
  description?: ReactNode
  footer?: ReactNode
  onPrev?: () => void
  onNext?: () => void
  children: ReactNode
}

/**
 * A record beside its list. It ignores a click outside, because a child dialog opened from the sheet would otherwise
 * close the sheet underneath it.
 */
export function RecordSheet({ open, onClose, ...props }: RecordSheetProps) {
  return <Sheet open={open} onClose={onClose} onInteractOutside={(event) => event.preventDefault()} {...props} />
}
