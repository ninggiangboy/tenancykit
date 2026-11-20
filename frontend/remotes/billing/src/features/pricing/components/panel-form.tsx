import { Button, Card } from '@suite/ui'
import { type ReactNode, useId } from 'react'
import type { FieldValues, UseFormReturn } from 'react-hook-form'

/** A form on the page (not in a dialog): its fields in two columns and one submit button. */
export function PanelForm<Input extends FieldValues, Output>({
  title,
  description,
  form,
  onSubmit,
  submitLabel,
  busy,
  children,
}: {
  title: string
  description: string
  form: UseFormReturn<Input, unknown, Output>
  onSubmit: (values: Output) => void
  submitLabel: string
  busy?: boolean
  children: ReactNode
}) {
  const id = useId()
  return (
    <Card title={title} description={description}>
      <form
        id={id}
        noValidate
        className="grid gap-3 md:grid-cols-2"
        onSubmit={(event) => void form.handleSubmit(onSubmit)(event)}
      >
        {children}
      </form>
      <div className="mt-4 flex justify-end">
        <Button type="submit" form={id} loading={busy}>
          {submitLabel}
        </Button>
      </div>
    </Card>
  )
}
