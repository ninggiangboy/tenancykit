import type { PaymentResponse } from '@suite/api-client/billing'
import { DetailRow, DetailRows, QueryState, RecordSheet, Section, Timestamp } from '@suite/remote-kit'
import { Button, CopyId, Money, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { CustomerCell, type CustomerName } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { usePayment, usePaymentPermissions } from '../hooks/use-payments'
import { RefundDialog } from './refund-dialog'

export interface PaymentSheetProps {
  payment: PaymentResponse
  names: Map<string, CustomerName>
  open: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function PaymentSheet({ payment, names, open, onClose, onPrev, onNext }: PaymentSheetProps) {
  const t = useT('payments')
  const tc = useT('common')
  const can = usePaymentPermissions()
  const detail = usePayment(payment.id)
  const [refunding, setRefunding] = useState(false)
  const currency = payment.amount?.currency
  const refundable = (payment.amount?.amount ?? 0) - (payment.refunded?.amount ?? 0)

  return (
    <>
      <RecordSheet
        open={open}
        onClose={onClose}
        eyebrow={
          <>
            {t('recordType')} · <CopyId value={payment.id ?? ''} />
          </>
        }
        title={<Money minor={payment.amount?.amount ?? 0} currency={currency} />}
        figure
        description={
          <>
            <StatusBadge status={payment.status} />
            <Timestamp value={payment.createdAt} />
          </>
        }
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={can.refund && payment.status === 'SUCCEEDED' && refundable > 0}>
            <Button variant="danger" icon="undo" onClick={() => setRefunding(true)}>
              {t('refund')}
            </Button>
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={t('refundable')}>
              <Money minor={refundable} currency={currency} />
            </DetailRow>
            <DetailRow label={tc('fields.customer')}>
              <CustomerCell id={payment.customerId} names={names} />
            </DetailRow>
            <DetailRow label={tc('fields.wallet')}>
              <CopyId value={payment.walletId ?? ''} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={payment.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={payment.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <Section title={t('refund')}>
            <QueryState
              query={detail}
              skeletonHeight={40}
              isEmpty={(data) => (data.refunds ?? []).length === 0}
              empty={<p className="text-small text-muted-foreground">{tc('state.none')}</p>}
            >
              {(data) => (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {(data.refunds ?? []).map((entry) => (
                    <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                      <span className="flex items-center gap-2">
                        <StatusBadge status={entry.status} />
                        <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
                      </span>
                      <span className="truncate text-muted-foreground">{entry.reason}</span>
                    </li>
                  ))}
                </ul>
              )}
            </QueryState>
          </Section>
        </div>
      </RecordSheet>
      <RefundDialog payment={payment} open={refunding} onClose={() => setRefunding(false)} />
    </>
  )
}
