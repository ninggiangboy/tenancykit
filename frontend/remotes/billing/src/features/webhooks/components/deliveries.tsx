import type { DeliveryResponse, EndpointResponse } from '@suite/api-client/billing'
import { ConfirmDialog, Section, Timestamp } from '@suite/remote-kit'
import { Button, PermissionGate, Skeleton, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useDeliveries, useResendDelivery } from '../hooks'

/** The endpoint's latest deliveries: one row each, with the last answer and a resend. */
export function Deliveries({ endpoint, canManage }: { endpoint: EndpointResponse; canManage: boolean }) {
  const t = useT('webhooks')
  const tc = useT('common')
  const query = useDeliveries(endpoint.id)
  const [target, setTarget] = useState<DeliveryResponse | null>(null)
  const resendIt = useResendDelivery(() => setTarget(null))
  const rows = query.data?.data ?? []

  return (
    <Section title={t('deliveries')}>
      {query.isLoading ? (
        <Skeleton width="100%" height={60} />
      ) : rows.length === 0 ? (
        <p className="text-small text-muted-foreground">{t('emptyDeliveries')}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {rows.map((delivery) => (
            <li key={delivery.id} className="flex items-center justify-between gap-3 px-3 py-2 text-small">
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="flex items-center gap-2">
                  <StatusBadge status={delivery.status} />
                  <span className="truncate font-mono text-code text-foreground">{delivery.eventType}</span>
                </span>
                <span className="truncate text-caption text-muted-foreground">
                  <Timestamp value={delivery.createdAt} /> · {t('attempts')} {delivery.attempts ?? 0} ·{' '}
                  {t('statusCode')} {delivery.lastStatusCode ?? delivery.lastError ?? tc('state.none')}
                </span>
              </span>
              <PermissionGate allowed={canManage}>
                <Button variant="ghost" size="sm" icon="refresh" onClick={() => setTarget(delivery)}>
                  {t('resend')}
                </Button>
              </PermissionGate>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.resendDelivery')}
        description={tc('confirm.resendDeliveryHint')}
        confirmLabel={t('resend')}
        destructive={false}
        busy={resendIt.isPending}
        onConfirm={() => target?.id && resendIt.mutate(target.id)}
        impact={[
          { label: t('eventTypes'), value: target?.eventType ?? '' },
          { label: t('attempts'), value: target?.attempts ?? 0 },
        ]}
      />
    </Section>
  )
}
