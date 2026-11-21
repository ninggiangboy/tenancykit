import type { SubscriptionResponse } from '@suite/api-client/billing'
import {
  ConfirmDialog,
  DetailRow,
  DetailRows,
  Maybe,
  QueryState,
  RecordSheet,
  Section,
  Timestamp,
} from '@suite/remote-kit'
import { Button, CopyId, Money, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useEnumLabel } from '../../../components/facets'
import { CustomerCell, type CustomerName } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useCancelSubscription, useCanManageSubscriptions, useSubscription } from '../hooks/use-subscriptions'

export interface SubscriptionSheetProps {
  subscription: SubscriptionResponse
  names: Map<string, CustomerName>
  open: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function SubscriptionSheet({ subscription, names, open, onClose, onPrev, onNext }: SubscriptionSheetProps) {
  const t = useT('subscriptions')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const canManage = useCanManageSubscriptions()
  const detail = useSubscription(subscription.id)
  const [canceling, setCanceling] = useState(false)
  const cancel = useCancelSubscription(() => setCanceling(false))

  return (
    <>
      <RecordSheet
        open={open}
        onClose={onClose}
        eyebrow={t('title')}
        title={subscription.planCode ?? ''}
        description={<StatusBadge status={subscription.status} />}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={canManage && subscription.status !== 'CANCELED'}>
            <Button variant="danger" icon="ban" onClick={() => setCanceling(true)}>
              {t('cancel')}
            </Button>
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.customer')}>
              <CustomerCell id={subscription.customerId} names={names} />
            </DetailRow>
            <DetailRow label={t('cycle')}>
              {subscription.cycle ? enumLabel(subscription.cycle) : <Maybe value={null} />}
            </DetailRow>
            <DetailRow label={t('periods')}>
              <Maybe value={subscription.periodsCharged} />
            </DetailRow>
            <DetailRow label={tc('fields.period')}>
              <Timestamp value={subscription.currentPeriodStart} /> –{' '}
              <Timestamp value={subscription.currentPeriodEnd} />
            </DetailRow>
            <DetailRow label={t('nextRenewal')}>
              <Timestamp value={subscription.nextRenewalAt} />
            </DetailRow>
            <DetailRow label={tc('fields.wallet')}>
              <CopyId value={subscription.walletId ?? ''} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={subscription.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <Section title={t('history')}>
            <QueryState
              query={detail}
              skeletonHeight={40}
              isEmpty={(data) => (data.history ?? []).length === 0}
              empty={<p className="text-small text-muted-foreground">{tc('state.none')}</p>}
            >
              {(data) => (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {(data.history ?? []).map((entry) => (
                    <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                      <span className="flex flex-col">
                        <span className="text-foreground">{entry.type}</span>
                        <span className="text-caption text-muted-foreground">
                          <Timestamp value={entry.occurredAt} />
                        </span>
                      </span>
                      <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
                    </li>
                  ))}
                </ul>
              )}
            </QueryState>
          </Section>
        </div>
      </RecordSheet>
      <ConfirmDialog
        open={canceling}
        onClose={() => setCanceling(false)}
        title={tc('confirm.cancelSubscription')}
        description={tc('confirm.cancelSubscriptionHint')}
        confirmLabel={t('cancel')}
        action={cancel}
        onConfirm={() => cancel.mutate(subscription.id ?? '')}
        impact={[
          { label: tc('fields.plan'), value: subscription.planCode ?? '' },
          { label: tc('fields.customer'), value: <CustomerCell id={subscription.customerId} names={names} /> },
        ]}
      />
    </>
  )
}
