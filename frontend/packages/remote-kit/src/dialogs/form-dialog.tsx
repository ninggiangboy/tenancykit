import { Button, Dialog } from '@suite/ui'
import { type ReactNode, useId } from 'react'
import type { FieldValues, UseFormReturn } from 'react-hook-form'
import { useKitT } from '../i18n/create-remote-i18n'

export interface FormDialogProps<Input extends FieldValues, Output> {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  submitLabel: string
  busy?: boolean
  form: UseFormReturn<Input, unknown, Output>
  onSubmit: (values: Output) => void
  size?: 'md' | 'lg'
  children: ReactNode
}

/**
 * A create or edit form in a modal. Closing it resets the form, so the next open starts clean; the caller closes it
 * from the mutation's success.
 */
export function FormDialog<Input extends FieldValues, Output>({
  open,
  onClose,
  title,
  description,
  submitLabel,
  busy,
  form,
  onSubmit,
  size,
  children,
}: FormDialogProps<Input, Output>) {
  const t = useKitT()
  const formId = useId()
  const close = () => {
    onClose()
    form.reset()
  }
  return (
    <Dialog
      open={open}
      onClose={close}
      title={title}
      description={description}
      size={size}
      footer={
        <>
          <Button variant="outline" size="sm" onClick={close} disabled={busy}>
            {t('actions.cancel')}
          </Button>
          <Button size="sm" type="submit" form={formId} loading={busy}>
            {submitLabel}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        noValidate
        className={size === 'lg' ? 'grid gap-3 sm:grid-cols-2' : 'flex flex-col gap-3'}
        onSubmit={(event) => void form.handleSubmit(onSubmit)(event)}
      >
        {children}
      </form>
    </Dialog>
  )
}
