import type { UserResponse } from '@suite/api-client/platform'
import { FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { useEffect } from 'react'
import { useT } from '../../../i18n'
import { useRenameUser } from '../hooks/use-users'
import { renameUserSchema } from '../schema'

export function RenameUserDialog({ user, open, onClose }: { user: UserResponse; open: boolean; onClose: () => void }) {
  const t = useT('users')
  const tc = useT('common')
  const form = useZodForm(renameUserSchema, { displayName: user.displayName ?? '' })
  const { reset } = form
  useEffect(() => {
    if (open) reset({ displayName: user.displayName ?? '' })
  }, [open, user.displayName, reset])
  const rename = useRenameUser(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('renameTitle')}
      submitLabel={tc('actions.save')}
      busy={rename.isPending}
      form={form}
      onSubmit={(values) => rename.mutate({ user, name: values.displayName })}
    >
      <FormField control={form.control} name="displayName" label={tc('fields.displayName')} autoFocus />
    </FormDialog>
  )
}
