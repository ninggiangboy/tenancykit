import type { InvoiceResponse } from '@suite/api-client/billing'
import { DetailRow, DetailRows, ListRow, QueryState, RecordSheet, RowList, Section, Timestamp } from '@suite/remote-kit'
import { Button, CopyId, Money, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { CustomerCell, type CustomerName } from '../../../components/lookups'
import { useT } from '../../../i18n'
import { useInvoice, useInvoicePermissions } from '../hooks/use-invoices'
import { type InvoiceAction, InvoiceActionDialog } from './invoice-dialogs'

export interface InvoiceSheetProps {
  invoice: InvoiceResponse
  names: Map<string, CustomerName>
  open: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function InvoiceSheet({ invoice, names, open, onClose, onPrev, onNext }: InvoiceSheetProps) {
  const t = useT('invoices')
  const tc = useT('common')
  const can = useInvoicePermissions()
  const detail = useInvoice(invoice.id)
  const [action, setAction] = useState<InvoiceAction | null>(null)
  const unpaid = invoice.status === 'OPEN'

  return (
    <>
      <RecordSheet
        open={open}
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
          unpaid ? (
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
            <QueryState
              query={detail}
              skeletonHeight={40}
              isEmpty={(data) => (data.items ?? []).length === 0}
              empty={<p className="text-small text-muted-foreground">{tc('state.none')}</p>}
            >
              {(data) => (
                <RowList>
                  {(data.items ?? []).map((item) => (
                    <ListRow
                      key={item.id}
                      title={item.description}
                      meta={item.source}
                      trailing={<Money minor={item.amount?.amount ?? 0} currency={item.amount?.currency} />}
                    />
                  ))}
                </RowList>
              )}
            </QueryState>
          </Section>
          <Section title={t('payments')}>
            <QueryState
              query={detail}
              skeletonHeight={40}
              isEmpty={(data) => (data.payments ?? []).length === 0}
              empty={<p className="text-small text-muted-foreground">{t('noPayments')}</p>}
            >
              {(data) => (
                <RowList>
                  {(data.payments ?? []).map((entry) => (
                    <ListRow
                      key={entry.paymentId}
                      lead={<StatusBadge status={entry.status} />}
                      title={<Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} />}
                      trailing={<CopyId value={entry.paymentId ?? ''} />}
                    />
                  ))}
                </RowList>
              )}
            </QueryState>
          </Section>
        </div>
      </RecordSheet>
      <InvoiceActionDialog invoice={invoice} action={action} onClose={() => setAction(null)} />
    </>
  )
}
