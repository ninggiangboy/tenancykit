import type { EventResponse, IncidentResponse } from '@suite/api-client/billing'
import { ConfirmDialog, ErrorPanel, Timestamp } from '@suite/remote-kit'
import { Button, Card, PermissionGate, Skeleton, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { type StripeActions, useStripeEvents, useStripeIncidents } from '../hooks'

export function StripeEvents({ canManage, actions }: { canManage: boolean; actions: StripeActions }) {
  const t = useT('stripe')
  const tc = useT('common')
  const query = useStripeEvents(true)
  const [target, setTarget] = useState<EventResponse | null>(null)
  const rows = query.data?.data ?? []
  return (
    <Card title={t('events')}>
      {query.error ? (
        <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <Skeleton width="100%" height={60} />
      ) : rows.length === 0 ? (
        <p className="text-small text-muted-foreground">{t('emptyEvents')}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {rows.map((event) => (
            <li key={event.id} className="flex items-center justify-between gap-3 px-3 py-2 text-small">
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="flex items-center gap-2">
                  <StatusBadge status={event.status} />
                  <span className="truncate font-mono text-code text-foreground">{event.type}</span>
                </span>
                <span className="truncate text-caption text-muted-foreground">
                  <Timestamp value={event.receivedAt} /> · {tc('fields.attempts')} {event.attempts ?? 0}
                  {event.lastError ? ` · ${event.lastError}` : ''}
                </span>
              </span>
              <PermissionGate allowed={canManage && (event.status === 'FAILED' || event.status === 'DEAD')}>
                <Button variant="ghost" size="sm" icon="refresh" onClick={() => setTarget(event)}>
                  {t('retry')}
                </Button>
              </PermissionGate>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.retryEvent')}
        description={tc('confirm.retryEventHint')}
        confirmLabel={t('retry')}
        destructive={false}
        action={actions.retry}
        onConfirm={() => target?.id && actions.retry.mutate(target.id, { onSuccess: () => setTarget(null) })}
        impact={[{ label: t('events'), value: <span className="font-mono">{target?.stripeEventId}</span> }]}
      />
    </Card>
  )
}

export function StripeIncidents({ canManage, actions }: { canManage: boolean; actions: StripeActions }) {
  const t = useT('stripe')
  const query = useStripeIncidents(true)
  const [target, setTarget] = useState<IncidentResponse | null>(null)
  const rows = query.data?.data ?? []
  return (
    <Card title={t('incidents')}>
      {query.error ? (
        <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <Skeleton width="100%" height={60} />
      ) : rows.length === 0 ? (
        <p className="text-small text-muted-foreground">{t('emptyIncidents')}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {rows.map((incident) => (
            <li key={incident.id} className="flex items-center justify-between gap-3 px-3 py-2 text-small">
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="text-body-strong text-foreground">{incident.kind}</span>
                <span className="truncate text-caption text-muted-foreground">{incident.detail}</span>
                <span className="text-caption text-muted-foreground">
                  ×{incident.occurrences} · <Timestamp value={incident.lastSeenAt} />
                </span>
              </span>
              {incident.resolvedAt ? (
                <StatusBadge tone="neutral">{t('resolvedBadge')}</StatusBadge>
              ) : (
                <PermissionGate allowed={canManage}>
                  <Button variant="outline" size="sm" onClick={() => setTarget(incident)}>
                    {t('resolve')}
                  </Button>
                </PermissionGate>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={t('resolve')}
        confirmLabel={t('resolve')}
        destructive={false}
        action={actions.resolve}
        onConfirm={() => target?.id && actions.resolve.mutate(target.id, { onSuccess: () => setTarget(null) })}
        impact={[{ label: t('incidents'), value: target?.kind ?? '' }]}
      />
    </Card>
  )
}
