import type { RoleResponse } from '@suite/api-client/platform'
import { FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { useEffect } from 'react'
import { useController } from 'react-hook-form'
import { PermissionEditor } from '../../../components/permission-editor'
import { useT } from '../../../i18n'
import { useCreateRole, useRenameRole } from '../hooks/use-roles'
import { createRoleSchema, renameRoleSchema } from '../schema'

export function CreateRoleDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('roles')
  const tc = useT('common')
  const form = useZodForm(createRoleSchema, { name: '', permissions: [] })
  const codes = useController({ control: form.control, name: 'permissions' })
  const create = useCreateRole(() => {
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
      busy={create.isPending}
      form={form}
      onSubmit={(values) => create.mutate(values)}
    >
      <FormField control={form.control} name="name" label={t('name')} autoFocus />
      <div className="flex flex-col gap-1.5">
        <span className="text-body-strong text-foreground">{tc('fields.permissions')}</span>
        <PermissionEditor value={codes.field.value ?? []} onChange={codes.field.onChange} />
      </div>
    </FormDialog>
  )
}

export function RenameRoleDialog({ role, open, onClose }: { role: RoleResponse; open: boolean; onClose: () => void }) {
  const t = useT('roles')
  const tc = useT('common')
  const form = useZodForm(renameRoleSchema, { name: role.name ?? '' })
  const { reset } = form
  useEffect(() => {
    if (open) reset({ name: role.name ?? '' })
  }, [open, role.name, reset])
  const rename = useRenameRole(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('renameTitle')}
      submitLabel={tc('actions.save')}
      busy={rename.isPending}
      form={form}
      onSubmit={(values) => rename.mutate({ role, name: values.name })}
    >
      <FormField control={form.control} name="name" label={t('name')} autoFocus />
    </FormDialog>
  )
}
