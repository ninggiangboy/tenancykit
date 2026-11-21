import type { CredentialResponse, SystemResponse } from '@suite/api-client/platform'
import {
  ConfirmDialog,
  errorMessage,
  FormDialog,
  FormField,
  SecretDialog,
  Section,
  Timestamp,
  useZodForm,
} from '@suite/remote-kit'
import { Alert, Button, PermissionGate, Skeleton, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useController } from 'react-hook-form'
import { PermissionEditor } from '../../../components/permission-editor'
import { useT } from '../../../i18n'
import { useCredentials, useIssueCredential, useRevokeCredential, useRotateCredential } from '../hooks/use-systems'
import { issueCredentialSchema, rotateSchema } from '../schema'

/** The system's credentials as a compact list, with issue, rotate and revoke; every new key is shown once. */
export function Credentials({ system, canManage }: { system: SystemResponse; canManage: boolean }) {
  const t = useT('systems')
  const query = useCredentials(system, canManage)
  const [dialog, setDialog] = useState<'issue' | 'rotate' | null>(null)
  const [revoking, setRevoking] = useState<CredentialResponse | null>(null)
  const [secret, setSecret] = useState<string | null>(null)
  const showSecret = (key: string | null) => {
    setDialog(null)
    setSecret(key)
  }
  const rows = query.data?.data ?? []
  const live = rows.some((row) => row.status !== 'REVOKED')

  return (
    <Section
      title={t('credentialsTitle')}
      description={t('credentialsDescription')}
      actions={
        <PermissionGate allowed={canManage}>
          {live && (
            <Button variant="outline" icon="refresh" onClick={() => setDialog('rotate')}>
              {t('rotate')}
            </Button>
          )}
          <Button icon="plus" onClick={() => setDialog('issue')}>
            {t('createCredential')}
          </Button>
        </PermissionGate>
      }
    >
      {!canManage ? null : query.error ? (
        <Alert tone="danger">{errorMessage(query.error)}</Alert>
      ) : query.isLoading ? (
        <Skeleton width="100%" height={80} />
      ) : rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border p-4 text-center text-small text-muted-foreground">
          {t('emptyCredentials')}
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {rows.map((row) => (
            <li key={row.id ?? row.keyId} className="flex items-center justify-between gap-3 px-3 py-2">
              <span className="flex min-w-0 flex-col">
                <span className="truncate text-body-strong text-foreground">{row.label ?? row.keyId}</span>
                <span className="truncate font-mono text-caption text-muted-foreground">{row.keyId}</span>
                <span className="text-caption text-muted-foreground">
                  {t('expires')}: <Timestamp value={row.expiresAt} />
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-2">
                <StatusBadge status={row.status} />
                <PermissionGate allowed={canManage && row.status !== 'REVOKED'}>
                  <Button variant="ghost" onClick={() => setRevoking(row)}>
                    {t('revoke')}
                  </Button>
                </PermissionGate>
              </span>
            </li>
          ))}
        </ul>
      )}
      <IssueCredentialDialog
        system={system}
        open={dialog === 'issue'}
        onClose={() => setDialog(null)}
        onSecret={showSecret}
      />
      <RotateCredentialDialog
        system={system}
        open={dialog === 'rotate'}
        onClose={() => setDialog(null)}
        onSecret={showSecret}
      />
      <RevokeCredentialDialog system={system} credential={revoking} onClose={() => setRevoking(null)} />
      <SecretDialog open={Boolean(secret)} onClose={() => setSecret(null)} secret={secret} />
    </Section>
  )
}

interface SecretProps {
  system: SystemResponse
  open: boolean
  onClose: () => void
  onSecret: (key: string | null) => void
}

function IssueCredentialDialog({ system, open, onClose, onSecret }: SecretProps) {
  const t = useT('systems')
  const tc = useT('common')
  const form = useZodForm(issueCredentialSchema, { label: '', scopes: [] })
  const scopes = useController({ control: form.control, name: 'scopes' })
  const issue = useIssueCredential(system, (key) => {
    form.reset()
    onSecret(key)
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('createCredential')}
      description={t('credentialsDescription')}
      submitLabel={tc('actions.create')}
      action={issue}
      form={form}
      onSubmit={(values) => issue.mutate(values)}
    >
      <FormField control={form.control} name="label" label={t('credentialLabel')} autoFocus />
      <div className="flex flex-col gap-1.5">
        <span className="text-body-strong text-foreground">{t('scopes')}</span>
        <span className="text-small text-muted-foreground">{t('scopesHint')}</span>
        <PermissionEditor value={scopes.field.value ?? []} onChange={scopes.field.onChange} />
      </div>
    </FormDialog>
  )
}

function RotateCredentialDialog({ system, open, onClose, onSecret }: SecretProps) {
  const t = useT('systems')
  const tc = useT('common')
  const form = useZodForm(rotateSchema, { overlapMinutes: '0' })
  const rotate = useRotateCredential(system, (key) => {
    form.reset()
    onSecret(key)
  })
  return (
    <FormDialog
      open={open}
      onClose={onClose}
      title={t('rotate')}
      description={tc('confirm.rotateCredentialHint')}
      submitLabel={t('rotate')}
      action={rotate}
      form={form}
      onSubmit={(values) => rotate.mutate(values.overlapMinutes ?? 0)}
    >
      <FormField
        control={form.control}
        name="overlapMinutes"
        label={t('overlap')}
        hint={t('overlapHint')}
        type="number"
        min={0}
      />
    </FormDialog>
  )
}

function RevokeCredentialDialog({
  system,
  credential,
  onClose,
}: {
  system: SystemResponse
  credential: CredentialResponse | null
  onClose: () => void
}) {
  const t = useT('systems')
  const tc = useT('common')
  const revoke = useRevokeCredential(system, onClose)
  return (
    <ConfirmDialog
      open={Boolean(credential)}
      onClose={onClose}
      title={tc('confirm.revokeCredential')}
      description={tc('confirm.revokeCredentialHint')}
      confirmLabel={t('revoke')}
      action={revoke}
      onConfirm={() => revoke.mutate(credential?.id ?? '')}
      impact={[
        { label: tc('fields.system'), value: system.name ?? '' },
        { label: tc('fields.label'), value: credential?.label ?? credential?.keyId ?? '' },
      ]}
    />
  )
}
