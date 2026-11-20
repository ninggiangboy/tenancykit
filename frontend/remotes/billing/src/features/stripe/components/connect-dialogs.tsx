import { ConfirmDialog, FormDialog, FormField, rules, useZodForm } from '@suite/remote-kit'
import { CopyId } from '@suite/ui'
import { z } from 'zod'
import { useT } from '../../../i18n'
import type { StripeActions } from '../hooks'

export type StripeDialog = 'onboarding' | 'keys' | 'secret' | 'disconnect' | null

const onboardingSchema = z.object({ displayName: rules.text(), contactEmail: rules.email() })
const keysSchema = z.object({
  secretKey: rules.text().pipe(z.string().regex(/^(sk|rk)_/, { message: 'form.invalid' })),
  publishableKey: rules.text().pipe(z.string().regex(/^pk_/, { message: 'form.invalid' })),
  webhookSecret: rules.optionalText(),
})
const secretSchema = z.object({
  webhookSecret: rules.text().pipe(z.string().regex(/^whsec_/, { message: 'form.invalid' })),
})

export function StripeDialogs({
  dialog,
  onClose,
  actions,
  accountId,
  onOnboarding,
}: {
  dialog: StripeDialog
  onClose: () => void
  actions: StripeActions
  accountId?: string
  onOnboarding: (url: string | null) => void
}) {
  const t = useT('stripe')
  const tc = useT('common')
  const onboarding = useZodForm(onboardingSchema, { displayName: '', contactEmail: '' })
  const keys = useZodForm(keysSchema, { secretKey: '', publishableKey: '', webhookSecret: '' })
  const secret = useZodForm(secretSchema, { webhookSecret: '' })
  const done = (reset: () => void) => () => {
    onClose()
    reset()
  }

  return (
    <>
      <FormDialog
        open={dialog === 'onboarding'}
        onClose={onClose}
        title={t('connectOnboarding')}
        description={t('connectOnboardingHint')}
        submitLabel={t('connect')}
        busy={actions.start.isPending}
        form={onboarding}
        onSubmit={(values) =>
          actions.start.mutate(values, {
            onSuccess: (answer) => {
              done(() => onboarding.reset())()
              onOnboarding(answer.onboardingUrl ?? null)
            },
          })
        }
      >
        <FormField control={onboarding.control} name="displayName" label={tc('fields.name')} autoFocus />
        <FormField control={onboarding.control} name="contactEmail" label={tc('fields.email')} type="email" />
      </FormDialog>
      <FormDialog
        open={dialog === 'keys'}
        onClose={onClose}
        title={t('connectKeys')}
        description={t('directHint')}
        submitLabel={tc('actions.save')}
        busy={actions.direct.isPending}
        form={keys}
        onSubmit={(values) => actions.direct.mutate(values, { onSuccess: done(() => keys.reset()) })}
      >
        <FormField
          control={keys.control}
          name="secretKey"
          label={t('secretKey')}
          type="password"
          autoComplete="off"
          mono
          placeholder="sk_live_…"
        />
        <FormField
          control={keys.control}
          name="publishableKey"
          label={t('publishableKey')}
          mono
          placeholder="pk_live_…"
        />
        <FormField
          control={keys.control}
          name="webhookSecret"
          label={t('webhookSecret')}
          type="password"
          autoComplete="off"
          mono
          optional
          placeholder="whsec_…"
        />
      </FormDialog>
      <FormDialog
        open={dialog === 'secret'}
        onClose={onClose}
        title={t('webhookSecret')}
        submitLabel={tc('actions.save')}
        busy={actions.webhookSecret.isPending}
        form={secret}
        onSubmit={(values) =>
          actions.webhookSecret.mutate(values.webhookSecret, { onSuccess: done(() => secret.reset()) })
        }
      >
        <FormField
          control={secret.control}
          name="webhookSecret"
          label={t('webhookSecret')}
          type="password"
          autoComplete="off"
          mono
          placeholder="whsec_…"
        />
      </FormDialog>
      <ConfirmDialog
        open={dialog === 'disconnect'}
        onClose={onClose}
        title={tc('confirm.disconnectStripe')}
        description={tc('confirm.disconnectStripeHint')}
        confirmLabel={t('disconnect')}
        busy={actions.disconnect.isPending}
        typed={{ label: tc('confirm.typeToConfirm', { value: 'disconnect' }), expected: 'disconnect' }}
        onConfirm={() => actions.disconnect.mutate(undefined, { onSuccess: onClose })}
        impact={[{ label: t('accountId'), value: <CopyId value={accountId ?? ''} /> }]}
      />
    </>
  )
}
