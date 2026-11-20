import type { InvoiceResponse } from '@suite/api-client/billing'
import { DetailRow, DetailRows, RecordSheet, Section, Timestamp } from '@suite/remote-kit'
import { Button, CopyId, Money, PermissionGate, Skeleton, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { CustomerCell, type CustomerName } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useInvoice, useInvoicePermissions } from '../hooks/use-invoices'
import { type InvoiceAction, InvoiceActionDialog } from './invoice-dialogs'

export interface InvoiceSheetProps {
  invoice: InvoiceResponse
  names: Map<string, CustomerName>
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function InvoiceSheet({ invoice, names, onClose, onPrev, onNext }: InvoiceSheetProps) {
  const t = useT('invoices')
  const tc = useT('common')
  const can = useInvoicePermissions()
  const detail = useInvoice(invoice.id)
  const [action, setAction] = useState<InvoiceAction | null>(null)
  const open = invoice.status === 'OPEN'
  const items = detail.data?.items ?? []
  const payments = detail.data?.payments ?? []

  return (
    <>
      <RecordSheet
        open
        onClose={onClose}
        eyebrow={
          <>
            {t('recordType')} · <CopyId value={invoice.number ?? invoice.id ?? ''} />
          </>
        }
        title={<Money minor={invoice.total?.amount ?? 0} currency={invoice.total?.currency} />}
        figure
        description={
          <>
            <StatusBadge status={invoice.status} />
            <Timestamp value={invoice.issuedAt} />
          </>
        }
        onPrev={onPrev}
        onNext={onNext}
        actions={
          open ? (
            <>
              <PermissionGate allowed={can.pay}>
                <Button icon="credit-card" onClick={() => setAction('pay')}>
                  {t('pay')}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={can.manage}>
                <Button variant="outline" icon="ban" onClick={() => setAction('uncollectible')}>
                  {t('uncollectible')}
                </Button>
                <Button variant="danger" icon="x-circle" onClick={() => setAction('void')}>
                  {t('void')}
                </Button>
              </PermissionGate>
            </>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.amount')}>
              <Money minor={invoice.total?.amount ?? 0} currency={invoice.total?.currency} />
            </DetailRow>
            <DetailRow label={tc('fields.customer')}>
              <CustomerCell id={invoice.customerId} names={names} />
            </DetailRow>
            <DetailRow label={tc('fields.wallet')}>
              <CopyId value={invoice.walletId ?? ''} />
            </DetailRow>
            <DetailRow label={t('issued')}>
              <Timestamp value={invoice.issuedAt} />
            </DetailRow>
            {(invoice.periodStart || invoice.periodEnd) && (
              <DetailRow label={tc('fields.period')}>
                <Timestamp value={invoice.periodStart} /> – <Timestamp value={invoice.periodEnd} />
              </DetailRow>
            )}
            <DetailRow label={tc('fields.id')}>
              <CopyId value={invoice.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <Section title={t('items')}>
            {detail.isLoading ? (
              <Skeleton width="100%" height={40} />
            ) : items.length === 0 ? (
              <p className="text-small text-muted-foreground">{tc('state.none')}</p>
            ) : (
              <ul className="divide-y divide-border rounded-lg border border-border">
                {items.map((item) => (
                  <li key={item.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                    <span className="flex min-w-0 flex-col">
                      <span className="truncate text-foreground">{item.description}</span>
                      <span className="text-caption text-muted-foreground">{item.source}</span>
                    </span>
                    <Money minor={item.amount?.amount ?? 0} currency={item.amount?.currency} />
                  </li>
                ))}
              </ul>
            )}
          </Section>
          <Section title={t('payments')}>
            {payments.length === 0 ? (
              <p className="text-small text-muted-foreground">{t('noPayments')}</p>
            ) : (
              <ul className="divide-y divide-border rounded-lg border border-border">
                {payments.map((entry) => (
                  <li key={entry.paymentId} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                    <span className="flex items-center gap-2">
                      <StatusBadge status={entry.status} />
                      <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />
                    </span>
                    <CopyId value={entry.paymentId ?? ''} />
                  </li>
                ))}
              </ul>
            )}
          </Section>
        </div>
      </RecordSheet>
      <InvoiceActionDialog invoice={invoice} action={action} onClose={() => setAction(null)} />
    </>
  )
}
