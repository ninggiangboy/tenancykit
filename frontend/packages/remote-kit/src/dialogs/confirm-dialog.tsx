import { Button, Dialog, DialogImpact, Field } from '@suite/ui'
import { type ReactNode, useEffect, useState } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'
import { type DialogAction, useDialogAction } from './dialog-action'

export interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  impact?: Array<{ label: ReactNode; value: ReactNode }>
  confirmLabel: string
  /** The mutation Confirm runs: its progress shows on the button and its failure inside the dialog. */
  action?: DialogAction
  destructive?: boolean
  /** A code the person retypes; Confirm stays disabled until it matches. */
  typed?: { label: string; expected: string }
  /** Extra controls (an amount, a reason); `confirmDisabled` keeps Confirm off until they are valid. */
  children?: ReactNode
  confirmDisabled?: boolean
  onConfirm: () => void
}

/** A decision that cannot be undone, with what it affects listed above the button. */
export function ConfirmDialog({
  open,
  onClose,
  title,
  description,
  impact,
  confirmLabel,
  action,
  destructive = true,
  typed,
  children,
  confirmDisabled,
  onConfirm,
}: ConfirmDialogProps) {
  const t = useKitT()
  const { busy, failure } = useDialogAction(open, action)
  const [typedValue, setTypedValue] = useState('')
  useEffect(() => {
    if (!open) setTypedValue('')
  }, [open])
  const mismatch = typed ? typedValue.trim() !== typed.expected : false

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      role="alertdialog"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>
            {t('actions.cancel')}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            loading={busy}
            disabled={mismatch || confirmDisabled}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {(impact?.length || children || typed || failure) && (
        <div className="flex flex-col gap-3">
          {impact && impact.length > 0 && <DialogImpact items={impact} />}
          {children}
          {typed && (
            <Field
              label={typed.label}
              value={typedValue}
              onChange={(event) => setTypedValue(event.target.value)}
              autoComplete="off"
              mono
            />
          )}
          {failure}
        </div>
      )}
    </Dialog>
  )
}
