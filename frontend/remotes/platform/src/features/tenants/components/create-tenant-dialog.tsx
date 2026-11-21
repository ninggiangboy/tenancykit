import { FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { useT } from '../../../i18n'
import { useCreateTenant } from '../hooks/use-tenants'
import { createTenantSchema } from '../schema'

export function CreateTenantDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('tenants')
  const tc = useT('common')
  const form = useZodForm(createTenantSchema, { code: '', name: '', adminEmail: '', adminTemporaryPassword: '' })
  const create = useCreateTenant(() => {
    onClose()
    form.reset()
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('createTitle')}
      description={t('createHint')}
      submitLabel={t('create')}
      action={create}
      form={form}
      onSubmit={(values) => create.mutate(values)}
    >
      <FormField control={form.control} name="code" label={tc('fields.code')} hint={t('codeHint')} mono autoFocus />
      <FormField control={form.control} name="name" label={tc('fields.name')} />
      <FormField control={form.control} name="adminEmail" label={t('adminEmail')} type="email" autoComplete="off" />
      <FormField
        control={form.control}
        name="adminTemporaryPassword"
        label={tc('fields.temporaryPassword')}
        type="password"
        autoComplete="new-password"
      />
    </FormDialog>
  )
}
