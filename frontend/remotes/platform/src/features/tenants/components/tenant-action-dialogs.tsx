import type { TenantResponse } from '@suite/api-client/platform'
import { ConfirmDialog, FormDialog, FormField, useZodForm } from '@suite/remote-kit'
import { Alert } from '@suite/ui'
import { useEffect } from 'react'
import { useT } from '../../../i18n'
import { useAddDomain, useDeactivateTenant, useRenameTenant } from '../hooks/use-tenants'
import { addDomainSchema, renameTenantSchema } from '../schema'

interface Props {
  tenant: TenantResponse
  open: boolean
  onClose: () => void
}

export function RenameTenantDialog({ tenant, open, onClose }: Props) {
  const tc = useT('common')
  const form = useZodForm(renameTenantSchema, { name: tenant.name ?? '' })
  const { reset } = form
  useEffect(() => {
    if (open) reset({ name: tenant.name ?? '' })
  }, [open, tenant.name, reset])
  const rename = useRenameTenant(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={tc('actions.rename')}
      submitLabel={tc('actions.save')}
      action={rename}
      form={form}
      onSubmit={(values) => rename.mutate({ id: tenant.id ?? '', version: tenant.version ?? 0, name: values.name })}
    >
      <FormField control={form.control} name="name" label={tc('fields.name')} autoFocus />
    </FormDialog>
  )
}

export function AddDomainDialog({ tenant, open, onClose }: Props) {
  const t = useT('tenants')
  const tc = useT('common')
  const form = useZodForm(addDomainSchema, { hostname: '' })
  const add = useAddDomain(() => {
    onClose()
    form.reset()
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('domainTitle')}
      submitLabel={tc('actions.addDomain')}
      action={add}
      form={form}
      onSubmit={(values) => add.mutate({ id: tenant.id ?? '', hostname: values.hostname })}
    >
      <FormField
        control={form.control}
        name="hostname"
        label={tc('fields.hostname')}
        placeholder={t('hostnamePlaceholder')}
        mono
        autoFocus
      />
      <Alert tone="info">{t('domainHint')}</Alert>
    </FormDialog>
  )
}

export function DeactivateTenantDialog({ tenant, open, onClose }: Props) {
  const t = useT('tenants')
  const tc = useT('common')
  const deactivate = useDeactivateTenant(onClose)
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      title={tc('confirm.deactivateTenant')}
      description={tc('confirm.deactivateTenantHint')}
      confirmLabel={tc('actions.deactivate')}
      action={deactivate}
      typed={{ label: tc('confirm.typeCode'), expected: tenant.code ?? '' }}
      impact={[
        { label: tc('fields.tenant'), value: tenant.code ?? '' },
        { label: t('apps'), value: t('noApps') },
      ]}
      onConfirm={() => deactivate.mutate({ id: tenant.id ?? '', version: tenant.version ?? 0 })}
    />
  )
}
