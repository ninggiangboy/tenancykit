import type { SystemResponse } from '@suite/api-client/platform'
import { ConfirmDialog, FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { StatusBadge } from '@suite/ui'
import { useEffect } from 'react'
import { useT } from '../../../i18n'
import { useCreateSystem, useUpdateSystem } from '../hooks/use-systems'
import { createSystemSchema, renameSystemSchema } from '../schema'

export function CreateSystemDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('systems')
  const tc = useT('common')
  const form = useZodForm(createSystemSchema, { name: '', purpose: '' })
  const create = useCreateSystem(() => {
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
      <FormField control={form.control} name="name" label={tc('fields.name')} autoFocus />
      <FormField control={form.control} name="purpose" label={t('purpose')} />
    </FormDialog>
  )
}

interface Props {
  system: SystemResponse
  open: boolean
  onClose: () => void
}

export function RenameSystemDialog({ system, open, onClose }: Props) {
  const t = useT('systems')
  const tc = useT('common')
  const form = useZodForm(renameSystemSchema, { name: system.name ?? '' })
  const { reset } = form
  useEffect(() => {
    if (open) reset({ name: system.name ?? '' })
  }, [open, system.name, reset])
  const update = useUpdateSystem(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('renameTitle')}
      submitLabel={tc('actions.save')}
      action={update}
      form={form}
      onSubmit={(values) => update.mutate({ id: system.id ?? '', action: 'RENAME', name: values.name })}
    >
      <FormField control={form.control} name="name" label={tc('fields.name')} autoFocus />
    </FormDialog>
  )
}

export function DisableSystemDialog({ system, open, onClose }: Props) {
  const t = useT('systems')
  const tc = useT('common')
  const update = useUpdateSystem(onClose)
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      title={tc('confirm.disableSystem')}
      description={tc('confirm.disableSystemHint')}
      confirmLabel={t('disable')}
      action={update}
      onConfirm={() => update.mutate({ id: system.id ?? '', action: 'DISABLE' })}
      impact={[
        { label: tc('fields.name'), value: system.name ?? '' },
        { label: tc('fields.status'), value: <StatusBadge status={system.status} /> },
      ]}
    />
  )
}
