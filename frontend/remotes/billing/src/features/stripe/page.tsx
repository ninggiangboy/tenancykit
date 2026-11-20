import { ErrorPanel, Page } from '@suite/remote-kit'
import { Alert, Button, PermissionGate, Skeleton } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../i18n'
import { type StripeDialog, StripeDialogs } from './components/connect-dialogs'
import { ConnectChoices, ConnectionCard } from './components/connection-card'
import { ReturnUrls } from './components/return-urls'
import { StripeEvents, StripeIncidents } from './components/stripe-activity'
import { useCanManageStripe, useStripeActions, useStripeConfiguration } from './hooks'

/** Not connected: only the two ways to connect. Connected: the connection, its events, incidents and return URLs. */
export function StripePage() {
  const t = useT('stripe')
  const canManage = useCanManageStripe()
  const configuration = useStripeConfiguration()
  const actions = useStripeActions()
  const [dialog, setDialog] = useState<StripeDialog>(null)
  const [onboardingUrl, setOnboardingUrl] = useState<string | null>(null)
  const [reconcileResult, setReconcileResult] = useState<string | null>(null)
  const config = configuration.data

  return (
    <Page
      title={t('title')}
      description={t('description')}
      actions={
        configuration.connected ? (
          <PermissionGate allowed={canManage}>
            <Button
              variant="outline"
              icon="repeat"
              loading={actions.reconcile.isPending}
              onClick={() =>
                actions.reconcile.mutate(undefined, {
                  onSuccess: (answer) =>
                    setReconcileResult(
                      `${t('checked')} ${answer.checked ?? 0} · ${t('corrected')} ${answer.corrected ?? 0} · ${t('mismatches')} ${answer.mismatches ?? 0}`,
                    ),
                })
              }
            >
              {t('reconcile')}
            </Button>
            <Button
              variant="outline"
              icon="refresh"
              loading={actions.refresh.isPending}
              onClick={() => actions.refresh.mutate(undefined)}
            >
              {t('refresh')}
            </Button>
          </PermissionGate>
        ) : undefined
      }
    >
      {onboardingUrl && (
        <Alert
          tone="info"
          title={t('connectHint')}
          action={
            <Button
              size="sm"
              iconEnd="arrow-up-right"
              onClick={() => globalThis.open(onboardingUrl, '_blank', 'noopener')}
            >
              {t('openOnboarding')}
            </Button>
          }
        />
      )}
      {configuration.isLoading ? (
        <Skeleton width="100%" height={160} />
      ) : configuration.failure ? (
        <ErrorPanel error={configuration.failure} onRetry={() => void configuration.refetch()} />
      ) : configuration.connected && config ? (
        <>
          <ConnectionCard
            config={config}
            canManage={canManage}
            onChoose={setDialog}
            reconcileResult={reconcileResult}
          />
          <div className="grid gap-4 lg:grid-cols-2">
            <StripeEvents canManage={canManage} actions={actions} />
            <StripeIncidents canManage={canManage} actions={actions} />
          </div>
          <ReturnUrls canManage={canManage} actions={actions} />
        </>
      ) : (
        <ConnectChoices canManage={canManage} onChoose={setDialog} />
      )}
      <StripeDialogs
        dialog={dialog}
        onClose={() => setDialog(null)}
        actions={actions}
        accountId={config?.accountId}
        onOnboarding={setOnboardingUrl}
      />
    </Page>
  )
}
