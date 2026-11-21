import { FormDialog, FormField, rules, useKitT, useZodForm } from '@suite/remote-kit'
import { TagInput } from '@suite/ui'
import { useController } from 'react-hook-form'
import { z } from 'zod'
import { useT } from '../../../i18n'
import { BILLING_EVENT_TYPES } from '../event-types'
import { useCreateEndpoint } from '../hooks'

const schema = z.object({
  url: rules.url(),
  description: rules.optionalText(),
  eventTypes: z.array(z.string()).min(1, { message: 'form.required' }),
})

export function CreateEndpointDialog({
  open,
  onClose,
  onSecret,
}: {
  open: boolean
  onClose: () => void
  onSecret: (secret: string | null) => void
}) {
  const t = useT('webhooks')
  const tc = useT('common')
  const tk = useKitT()
  const form = useZodForm(schema, { url: '', description: '', eventTypes: [] })
  const events = useController({ control: form.control, name: 'eventTypes' })
  const create = useCreateEndpoint((secret) => {
    onClose()
    form.reset()
    onSecret(secret)
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('createTitle')}
      description={t('createHint')}
      submitLabel={tc('actions.create')}
      action={create}
      form={form}
      onSubmit={(values) => create.mutate(values)}
    >
      <FormField
        control={form.control}
        name="url"
        label={t('url')}
        type="url"
        placeholder={t('urlPlaceholder')}
        mono
        autoFocus
      />
      <FormField control={form.control} name="description" label={t('descriptionField')} optional />
      <TagInput
        label={t('eventTypes')}
        hint={t('eventTypesHint')}
        error={events.fieldState.error ? tk('form.required') : undefined}
        value={events.field.value ?? []}
        onChange={events.field.onChange}
        suggestions={BILLING_EVENT_TYPES}
        placeholder="invoice.created"
      />
    </FormDialog>
  )
}
