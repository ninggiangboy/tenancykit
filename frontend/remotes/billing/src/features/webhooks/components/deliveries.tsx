import type { DeliveryResponse, EndpointResponse } from '@suite/api-client/billing'
import { ConfirmDialog, ListRow, QueryState, RowList, Section, Timestamp } from '@suite/remote-kit'
import { Button, PermissionGate, StatusBadge } from '@suite/ui'
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

  return (
    <Section title={t('deliveries')}>
      <QueryState
        query={query}
        skeletonHeight={60}
        isEmpty={(data) => (data.data ?? []).length === 0}
        empty={<p className="text-small text-muted-foreground">{t('emptyDeliveries')}</p>}
      >
        {(data) => (
          <RowList>
            {(data.data ?? []).map((delivery) => (
              <ListRow
                key={delivery.id}
                lead={<StatusBadge status={delivery.status} />}
                title={<span className="font-mono text-code">{delivery.eventType}</span>}
                meta={
                  <>
                    <Timestamp value={delivery.createdAt} /> · {t('attempts')} {delivery.attempts ?? 0} ·{' '}
                    {t('statusCode')} {delivery.lastStatusCode ?? delivery.lastError ?? tc('state.none')}
                  </>
                }
                trailing={
                  <PermissionGate allowed={canManage}>
                    <Button variant="ghost" size="sm" icon="refresh" onClick={() => setTarget(delivery)}>
                      {t('resend')}
                    </Button>
                  </PermissionGate>
                }
              />
            ))}
          </RowList>
        )}
      </QueryState>
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.resendDelivery')}
        description={tc('confirm.resendDeliveryHint')}
        confirmLabel={t('resend')}
        destructive={false}
        action={resendIt}
        onConfirm={() => target?.id && resendIt.mutate(target.id)}
        impact={[
          { label: t('eventTypes'), value: target?.eventType ?? '' },
          { label: t('attempts'), value: target?.attempts ?? 0 },
        ]}
      />
    </Section>
  )
}
