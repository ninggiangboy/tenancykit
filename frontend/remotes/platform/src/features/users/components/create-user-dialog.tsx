import { FormDialog, FormEntityPicker, FormField, useZodForm } from '@suite/remote-kit'
import { useShell } from '@suite/shell-sdk/react'
import { useTenantOptions } from '../../../components/tenant-lookup'
import { useT } from '../../../i18n'
import { useCreateUser } from '../hooks/use-users'
import { createUserSchema } from '../schema'

const useTenantCodeOptions = (search: string) => useTenantOptions(search, 'code')

export function CreateUserDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('users')
  const tc = useT('common')
  const platformScope = useShell().user.scope === 'PLATFORM'
  const form = useZodForm(createUserSchema, { tenantCode: '', displayName: '', email: '', temporaryPassword: '' })
  const create = useCreateUser(() => {
    onClose()
    form.reset()
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
      {platformScope && (
        <FormEntityPicker
          control={form.control}
          name="tenantCode"
          label={t('tenantCode')}
          hint={t('tenantHint')}
          optional
          useOptions={useTenantCodeOptions}
        />
      )}
      <FormField control={form.control} name="displayName" label={tc('fields.displayName')} />
      <FormField control={form.control} name="email" label={tc('fields.email')} type="email" autoComplete="off" />
      <FormField
        control={form.control}
        name="temporaryPassword"
        label={t('temporaryPassword')}
        type="password"
        autoComplete="new-password"
      />
    </FormDialog>
  )
}
