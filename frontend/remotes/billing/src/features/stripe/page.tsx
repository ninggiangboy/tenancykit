import { permissions } from '@suite/api-client'
import type { EventResponse, IncidentResponse } from '@suite/api-client/billing'
import {
  connectDirect,
  disconnect as disconnectStripe,
  reconcile,
  refresh,
  replace as replaceReturnUrls,
  resolveIncident,
  retry,
  setWebhookSecret,
  startConnect,
} from '@suite/api-client/billing'
import { eventsOptions, getConfigurationOptions, incidentsOptions, listOptions } from '@suite/api-client/billing/query'
import { usePermission } from '@suite/shell-sdk/react'
import { Alert, Button, Card, CopyId, Field, PermissionGate, StatusBadge } from '@suite/ui'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useT } from '../../i18n'
import { ConfirmDialog, DetailRow, DetailRows, Forbidden, FormDialog, PageHeader, Timestamp } from '../../lib/page'
import { errorMessage, errorStatus, useAction, useApi, useToast } from '../../lib/runtime'

export function StripePage() {
  const t = useT('stripe')
  const tc = useT('common')
  const toast = useToast()
  const { billing } = useApi()
  const canRead = usePermission(permissions.billing.getConfiguration)
  const canManage = usePermission(permissions.billing.startConnect)

  const configuration = useQuery({
    ...getConfigurationOptions({ client: billing, throwOnError: true }),
    enabled: canRead,
  })
  const events = useQuery({
    ...eventsOptions({
      client: billing,
      throwOnError: true,
      query: { limit: '20', page_info: 'full' } as never,
    }),
    enabled: canRead,
  })
  const incidents = useQuery({
    ...incidentsOptions({
      client: billing,
      throwOnError: true,
      query: { limit: '20', page_info: 'full' } as never,
    }),
    enabled: canRead,
  })
  const returnUrls = useQuery({
    ...listOptions({ client: billing, throwOnError: true }),
    enabled: canRead,
  })

  const [connectOpen, setConnectOpen] = useState(false)
  const [connect, setConnect] = useState({ displayName: '', contactEmail: '' })
  const [directOpen, setDirectOpen] = useState(false)
  const [direct, setDirect] = useState({ secretKey: '', publishableKey: '', webhookSecret: '' })
  const [secretOpen, setSecretOpen] = useState(false)
  const [webhookSecret, setWebhookSecretValue] = useState('')
  const [disconnectOpen, setDisconnectOpen] = useState(false)
  const [typed, setTyped] = useState('')
  const [onboardingUrl, setOnboardingUrl] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [urls, setUrls] = useState('')
  const [retryTarget, setRetryTarget] = useState<EventResponse | null>(null)
  const [resolveTarget, setResolveTarget] = useState<IncidentResponse | null>(null)

  useEffect(() => {
    setUrls((returnUrls.data ?? []).join('\n'))
  }, [returnUrls.data])

  const start = useAction(
    (vars: { displayName: string; contactEmail: string }) =>
      startConnect({
        client: billing,
        throwOnError: true,
        body: { displayName: vars.displayName.trim(), contactEmail: vars.contactEmail.trim() },
      }),
    {
      onSuccess: (response) => {
        setConnectOpen(false)
        setOnboardingUrl(response.data.onboardingUrl ?? null)
        toast({ title: t('connected'), tone: 'success' })
      },
    },
  )

  const saveDirect = useAction(
    (vars: { secretKey: string; publishableKey: string; webhookSecret: string }) =>
      connectDirect({
        client: billing,
        throwOnError: true,
        body: {
          secretKey: vars.secretKey,
          publishableKey: vars.publishableKey,
          webhookSecret: vars.webhookSecret || undefined,
        },
      }),
    {
      onSuccess: () => {
        setDirectOpen(false)
        setDirect({ secretKey: '', publishableKey: '', webhookSecret: '' })
        toast({ title: t('connected'), tone: 'success' })
      },
    },
  )

  const saveWebhookSecret = useAction(
    (vars: { secret: string }) =>
      setWebhookSecret({ client: billing, throwOnError: true, body: { webhookSecret: vars.secret } }),
    {
      onSuccess: () => {
        setSecretOpen(false)
        setWebhookSecretValue('')
        toast({ title: t('saved'), tone: 'success' })
      },
    },
  )

  const disconnect = useAction(() => disconnectStripe({ client: billing, throwOnError: true }), {
    onSuccess: () => {
      setDisconnectOpen(false)
      setTyped('')
      toast({ title: t('disconnected'), tone: 'success' })
    },
  })

  const refreshStatus = useAction(() => refresh({ client: billing, throwOnError: true }), {
    onSuccess: () => toast({ title: t('refreshed'), tone: 'success' }),
  })

  const runReconcile = useAction(() => reconcile({ client: billing, throwOnError: true }), {
    onSuccess: (response) =>
      setResult(
        `${t('checked')} ${response.data.checked ?? 0} · ${t('corrected')} ${response.data.corrected ?? 0} · ${t('mismatches')} ${response.data.mismatches ?? 0}`,
      ),
  })

  const saveUrls = useAction(
    (vars: { urls: string[] }) => replaceReturnUrls({ client: billing, throwOnError: true, body: { urls: vars.urls } }),
    { onSuccess: () => toast({ title: t('saved'), tone: 'success' }) },
  )

  const retryEvent = useAction(
    (vars: { id: string }) => retry({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setRetryTarget(null)
        toast({ title: t('retried'), tone: 'success' })
      },
    },
  )

  const resolve = useAction(
    (vars: { id: string }) => resolveIncident({ client: billing, throwOnError: true, path: { id: vars.id } }),
    {
      onSuccess: () => {
        setResolveTarget(null)
        toast({ title: t('resolved'), tone: 'success' })
      },
    },
  )

  if (!canRead) return <Forbidden />

  const config = configuration.data

  return (
    <div className="flex flex-col">
      <PageHeader title={t('title')} description={t('description')}>
        <PermissionGate allowed={canManage}>
          <Button variant="outline" icon="refresh" onClick={() => refreshStatus.mutate(undefined)}>
            {t('refresh')}
          </Button>
          <Button icon="external" onClick={() => setConnectOpen(true)}>
            {t('connect')}
          </Button>
        </PermissionGate>
      </PageHeader>
      <div className="flex flex-col gap-4 px-6 pb-8">
        <Card
          title={t('mode')}
          description={config?.webhookUrl}
          action={
            <PermissionGate allowed={canManage}>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" icon="key" onClick={() => setDirectOpen(true)}>
                  {t('direct')}
                </Button>
                <Button variant="outline" size="sm" onClick={() => setSecretOpen(true)}>
                  {t('webhookSecret')}
                </Button>
                <Button variant="destructive" size="sm" icon="ban" onClick={() => setDisconnectOpen(true)}>
                  {t('disconnect')}
                </Button>
              </div>
            </PermissionGate>
          }
        >
          {configuration.error && errorStatus(configuration.error) !== 404 ? (
            <Alert tone="danger" title={tc('state.error')}>
              {errorMessage(configuration.error)}
            </Alert>
          ) : config ? (
            <DetailRows>
              <DetailRow label={t('mode')}>{config.mode ?? tc('state.none')}</DetailRow>
              <DetailRow label={t('accountId')}>
                <CopyId value={config.accountId ?? ''} />
              </DetailRow>
              <DetailRow label={t('status')}>
                <StatusBadge status={config.status} />
              </DetailRow>
              <DetailRow label={t('cardPayments')}>
                {config.cardPaymentsActive ? tc('state.on') : tc('state.off')}
              </DetailRow>
              <DetailRow label={t('publishableKey')}>
                <span className="font-mono text-code">{config.publishableKey ?? tc('state.none')}</span>
              </DetailRow>
              <DetailRow label={t('webhookSecret')}>
                {config.webhookSecretSet ? tc('state.on') : tc('state.off')}
              </DetailRow>
              <DetailRow label={tc('fields.updated')}>
                <Timestamp value={config.updatedAt} />
              </DetailRow>
            </DetailRows>
          ) : (
            <p className="text-small text-muted-foreground">{t('notConnected')}</p>
          )}
          {onboardingUrl && (
            <Alert tone="info" title={t('connectHint')}>
              <CopyId value={onboardingUrl} />
            </Alert>
          )}
          {result && (
            <Alert tone="info" title={t('result')}>
              {result}
            </Alert>
          )}
          <div className="mt-3 flex justify-end">
            <PermissionGate allowed={canManage}>
              <Button
                variant="outline"
                size="sm"
                icon="repeat"
                loading={runReconcile.isPending}
                onClick={() => runReconcile.mutate(undefined)}
              >
                {t('reconcile')}
              </Button>
            </PermissionGate>
          </div>
        </Card>

        <Card title={t('events')}>
          {events.data?.data && events.data.data.length > 0 ? (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {events.data.data.map((event) => (
                <li key={event.id} className="flex items-start justify-between gap-2 px-3 py-2 text-small">
                  <span className="flex flex-col">
                    <span className="flex items-center gap-2">
                      <StatusBadge status={event.status} />
                      <span className="text-foreground">{event.type}</span>
                    </span>
                    <span className="font-mono text-caption text-muted-foreground">{event.stripeEventId}</span>
                    <span className="text-caption text-muted-foreground">
                      {tc('fields.attempts')}: {event.attempts ?? 0} · {event.lastError}
                    </span>
                  </span>
                  <span className="flex items-center gap-2">
                    <Timestamp value={event.receivedAt} />
                    <PermissionGate allowed={canManage}>
                      <Button variant="ghost" size="sm" onClick={() => setRetryTarget(event)}>
                        {t('retry')}
                      </Button>
                    </PermissionGate>
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-small text-muted-foreground">{t('emptyEvents')}</p>
          )}
        </Card>

        <Card title={t('incidents')}>
          {incidents.data?.data && incidents.data.data.length > 0 ? (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {incidents.data.data.map((incident) => (
                <li key={incident.id} className="flex items-start justify-between gap-2 px-3 py-2 text-small">
                  <span className="flex flex-col">
                    <span className="text-body-strong text-foreground">{incident.kind}</span>
                    <span className="text-caption text-muted-foreground">{incident.detail}</span>
                    <span className="text-caption text-muted-foreground">
                      {incident.occurrences} · <Timestamp value={incident.lastSeenAt} />
                    </span>
                  </span>
                  <PermissionGate allowed={canManage}>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={Boolean(incident.resolvedAt)}
                      onClick={() => setResolveTarget(incident)}
                    >
                      {t('resolve')}
                    </Button>
                  </PermissionGate>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-small text-muted-foreground">{t('emptyIncidents')}</p>
          )}
        </Card>

        <Card
          title={t('returnUrls')}
          description={t('urlsHint')}
          action={
            <PermissionGate allowed={canManage}>
              <Button
                size="sm"
                loading={saveUrls.isPending}
                onClick={() =>
                  saveUrls.mutate({
                    urls: urls
                      .split('\n')
                      .map((entry) => entry.trim())
                      .filter(Boolean),
                  })
                }
              >
                {t('replaceUrls')}
              </Button>
            </PermissionGate>
          }
        >
          <Field
            label={t('returnUrls')}
            hideLabel
            type="textarea"
            mono
            value={urls}
            onChange={(event) => setUrls(event.target.value)}
            hint={t('urlsHint')}
          />
        </Card>
      </div>

      <FormDialog
        open={connectOpen}
        onClose={() => setConnectOpen(false)}
        title={t('connect')}
        description={t('connectHint')}
        submitLabel={t('connect')}
        busy={start.isPending}
        onSubmit={() => start.mutate(connect)}
      >
        <Field
          label={tc('fields.name')}
          value={connect.displayName}
          onChange={(event) => setConnect({ ...connect, displayName: event.target.value })}
          required
        />
        <Field
          label={tc('fields.email')}
          type="email"
          value={connect.contactEmail}
          onChange={(event) => setConnect({ ...connect, contactEmail: event.target.value })}
          required
        />
      </FormDialog>

      <FormDialog
        open={directOpen}
        onClose={() => setDirectOpen(false)}
        title={t('direct')}
        description={t('directHint')}
        submitLabel={tc('actions.save')}
        busy={saveDirect.isPending}
        onSubmit={() => saveDirect.mutate(direct)}
      >
        <Field
          label={t('secretKey')}
          type="password"
          value={direct.secretKey}
          onChange={(event) => setDirect({ ...direct, secretKey: event.target.value })}
          required
        />
        <Field
          label={t('publishableKey')}
          value={direct.publishableKey}
          onChange={(event) => setDirect({ ...direct, publishableKey: event.target.value })}
          mono
        />
        <Field
          label={t('webhookSecret')}
          type="password"
          value={direct.webhookSecret}
          onChange={(event) => setDirect({ ...direct, webhookSecret: event.target.value })}
          optional
        />
      </FormDialog>

      <FormDialog
        open={secretOpen}
        onClose={() => setSecretOpen(false)}
        title={t('webhookSecret')}
        submitLabel={tc('actions.save')}
        busy={saveWebhookSecret.isPending}
        onSubmit={() => saveWebhookSecret.mutate({ secret: webhookSecret })}
      >
        <Field
          label={t('webhookSecret')}
          type="password"
          value={webhookSecret}
          onChange={(event) => setWebhookSecretValue(event.target.value)}
          required
        />
      </FormDialog>

      <ConfirmDialog
        open={disconnectOpen}
        onClose={() => setDisconnectOpen(false)}
        title={tc('confirm.disconnectStripe')}
        description={tc('confirm.disconnectStripeHint')}
        confirmLabel={t('disconnect')}
        busy={disconnect.isPending}
        typed={{
          label: tc('confirm.typeToConfirm', { value: 'disconnect' }),
          expected: 'disconnect',
          value: typed,
          onChange: setTyped,
        }}
        onConfirm={() => disconnect.mutate(undefined)}
        impact={[{ label: t('accountId'), value: <CopyId value={config?.accountId ?? ''} /> }]}
      />

      <ConfirmDialog
        open={Boolean(retryTarget)}
        onClose={() => setRetryTarget(null)}
        title={tc('confirm.retryEvent')}
        description={tc('confirm.retryEventHint')}
        confirmLabel={t('retry')}
        destructive={false}
        busy={retryEvent.isPending}
        onConfirm={() => {
          if (retryTarget?.id) retryEvent.mutate({ id: retryTarget.id })
        }}
        impact={[{ label: t('events'), value: retryTarget?.stripeEventId ?? '' }]}
      />

      <ConfirmDialog
        open={Boolean(resolveTarget)}
        onClose={() => setResolveTarget(null)}
        title={t('resolve')}
        confirmLabel={t('resolve')}
        destructive={false}
        busy={resolve.isPending}
        onConfirm={() => {
          if (resolveTarget?.id) resolve.mutate({ id: resolveTarget.id })
        }}
        impact={[{ label: t('incidents'), value: resolveTarget?.kind ?? '' }]}
      />
    </div>
  )
}
