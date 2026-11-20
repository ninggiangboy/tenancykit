import type { ConfigurationResponse } from '@suite/api-client/billing'
import { DetailRow, DetailRows, Maybe, Timestamp } from '@suite/remote-kit'
import { Alert, Button, Card, CopyId, Icon, PermissionGate, StatusBadge } from '@suite/ui'
import { useT } from '../../../i18n'
import type { StripeDialog } from './connect-dialogs'

/** Two ways to connect, side by side; nothing about a connection is offered before there is one. */
export function ConnectChoices({
  canManage,
  onChoose,
}: {
  canManage: boolean
  onChoose: (dialog: StripeDialog) => void
}) {
  const t = useT('stripe')
  const choices = [
    {
      id: 'onboarding' as const,
      icon: 'external' as const,
      title: t('connectOnboarding'),
      hint: t('connectOnboardingHint'),
    },
    { id: 'keys' as const, icon: 'key' as const, title: t('connectKeys'), hint: t('connectKeysHint') },
  ]
  return (
    <Card title={t('connectTitle')} description={t('connectIntro')}>
      <div className="grid gap-3 sm:grid-cols-2">
        {choices.map((choice) => (
          <div key={choice.id} className="flex flex-col gap-3 rounded-lg border border-border p-4">
            <span className="flex items-center gap-2 text-body-strong text-foreground">
              <Icon name={choice.icon} size={16} />
              {choice.title}
            </span>
            <p className="flex-1 text-small text-muted-foreground">{choice.hint}</p>
            <PermissionGate allowed={canManage}>
              <Button
                variant={choice.id === 'onboarding' ? 'default' : 'outline'}
                size="sm"
                onClick={() => onChoose(choice.id)}
              >
                {choice.title}
              </Button>
            </PermissionGate>
          </div>
        ))}
      </div>
    </Card>
  )
}

export function ConnectionCard({
  config,
  canManage,
  onChoose,
  reconcileResult,
}: {
  config: ConfigurationResponse
  canManage: boolean
  onChoose: (dialog: StripeDialog) => void
  reconcileResult?: string | null
}) {
  const t = useT('stripe')
  const tc = useT('common')
  return (
    <Card
      title={t('connectionTitle')}
      action={
        <PermissionGate allowed={canManage}>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" icon="key" onClick={() => onChoose('secret')}>
              {t('webhookSecret')}
            </Button>
            <Button variant="destructive" size="sm" icon="ban" onClick={() => onChoose('disconnect')}>
              {t('disconnect')}
            </Button>
          </div>
        </PermissionGate>
      }
    >
      <div className="flex flex-col gap-3">
        {!config.webhookSecretSet && <Alert tone="warning">{t('secretMissing')}</Alert>}
        <DetailRows>
          <DetailRow label={t('status')}>
            <StatusBadge status={config.status} />
          </DetailRow>
          <DetailRow label={t('mode')}>
            <Maybe value={config.mode} />
          </DetailRow>
          <DetailRow label={t('accountId')}>
            <Maybe value={config.accountId}>
              <CopyId value={config.accountId ?? ''} />
            </Maybe>
          </DetailRow>
          <DetailRow label={t('cardPayments')}>
            {config.cardPaymentsActive ? tc('state.on') : tc('state.off')}
          </DetailRow>
          <DetailRow label={t('publishableKey')}>
            <span className="font-mono text-code">
              <Maybe value={config.publishableKey} />
            </span>
          </DetailRow>
          <DetailRow label={t('webhookUrl')}>
            <Maybe value={config.webhookUrl}>
              <CopyId value={config.webhookUrl ?? ''} />
            </Maybe>
          </DetailRow>
          <DetailRow label={tc('fields.updated')}>
            <Timestamp value={config.updatedAt} />
          </DetailRow>
        </DetailRows>
        {reconcileResult && (
          <Alert tone="info" title={t('result')}>
            {reconcileResult}
          </Alert>
        )}
      </div>
    </Card>
  )
}
