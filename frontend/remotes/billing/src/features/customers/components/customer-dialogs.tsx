import type { CustomerResponse } from '@suite/api-client/billing'
import { ConfirmDialog, FormDialog, FormEntityPicker, FormField, useZodForm } from '@suite/remote-kit'
import { useEffect } from 'react'
import { useT } from '../../../i18n'
import {
  useCreateCustomer,
  useDeactivateCustomer,
  useTenantSystemOptions,
  useUpdateCustomer,
} from '../hooks/use-customers'
import { createCustomerSchema, editCustomerSchema } from '../schema'

export function CreateCustomerDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('customers')
  const tc = useT('common')
  const form = useZodForm(createCustomerSchema, { displayName: '', email: '', tenantSystemId: '', externalUserId: '' })
  const create = useCreateCustomer(() => {
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
      busy={create.isPending}
      form={form}
      onSubmit={(values) => create.mutate(values)}
    >
      <FormField control={form.control} name="displayName" label={tc('fields.displayName')} autoFocus />
      <FormField control={form.control} name="email" label={tc('fields.email')} type="email" optional />
      <FormEntityPicker
        control={form.control}
        name="tenantSystemId"
        label={t('identitySystem')}
        hint={t('identityHint')}
        optional
        useOptions={useTenantSystemOptions}
      />
      <FormField control={form.control} name="externalUserId" label={t('identityUser')} mono optional />
    </FormDialog>
  )
}

interface Props {
  customer: CustomerResponse
  open: boolean
  onClose: () => void
}

export function EditCustomerDialog({ customer, open, onClose }: Props) {
  const t = useT('customers')
  const tc = useT('common')
  const defaults = { displayName: customer.displayName ?? '', email: customer.email ?? '' }
  const form = useZodForm(editCustomerSchema, defaults)
  const { reset } = form
  useEffect(() => {
    if (open) reset(defaults)
  }, [open, customer.displayName, customer.email, reset])
  const update = useUpdateCustomer(onClose)
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('editTitle')}
      submitLabel={tc('actions.save')}
      busy={update.isPending}
      form={form}
      onSubmit={(values) => update.mutate({ id: customer.id ?? '', ...values })}
    >
      <FormField control={form.control} name="displayName" label={tc('fields.displayName')} autoFocus />
      <FormField control={form.control} name="email" label={tc('fields.email')} type="email" optional />
    </FormDialog>
  )
}

export function DeactivateCustomerDialog({ customer, open, onClose }: Props) {
  const t = useT('customers')
  const tc = useT('common')
  const deactivate = useDeactivateCustomer(onClose)
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      title={tc('confirm.deactivateCustomer')}
      description={tc('confirm.deactivateCustomerHint')}
      confirmLabel={t('deactivate')}
      busy={deactivate.isPending}
      onConfirm={() => deactivate.mutate(customer.id ?? '')}
      impact={[
        { label: tc('fields.customer'), value: customer.displayName ?? '' },
        { label: tc('fields.email'), value: customer.email ?? tc('state.none') },
      ]}
    />
  )
}
