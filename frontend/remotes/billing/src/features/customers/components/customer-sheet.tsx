import type { CustomerResponse } from '@suite/api-client/billing'
import { DetailRow, DetailRows, Maybe, RecordSheet, Section, Timestamp } from '@suite/remote-kit'
import { Button, CopyId, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useCanWriteCustomers } from '../hooks/use-customers'
import { DeactivateCustomerDialog, EditCustomerDialog } from './customer-dialogs'

export interface CustomerSheetProps {
  customer: CustomerResponse
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function CustomerSheet({ customer, onClose, onPrev, onNext }: CustomerSheetProps) {
  const t = useT('customers')
  const tc = useT('common')
  const canWrite = useCanWriteCustomers()
  const [dialog, setDialog] = useState<'edit' | 'deactivate' | null>(null)
  const identities = customer.identities ?? []

  return (
    <>
      <RecordSheet
        open
        onClose={onClose}
        eyebrow={t('title')}
        title={customer.displayName ?? ''}
        description={customer.email}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={canWrite}>
            <Button variant="outline" icon="settings" onClick={() => setDialog('edit')}>
              {tc('actions.edit')}
            </Button>
            {customer.status !== 'DEACTIVATED' && (
              <Button variant="danger" icon="ban" onClick={() => setDialog('deactivate')}>
                {t('deactivate')}
              </Button>
            )}
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={customer.status} />
            </DetailRow>
            <DetailRow label={tc('fields.email')}>
              <Maybe value={customer.email} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={customer.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={customer.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <Section title={t('identities')}>
            {identities.length === 0 ? (
              <p className="text-small text-muted-foreground">{tc('state.none')}</p>
            ) : (
              <DetailRows>
                {identities.map((identity) => (
                  <DetailRow
                    key={`${identity.tenantSystemId}:${identity.externalUserId}`}
                    label={<span className="font-mono text-caption">{identity.externalUserId}</span>}
                  >
                    <CopyId value={identity.tenantSystemId ?? ''} />
                  </DetailRow>
                ))}
              </DetailRows>
            )}
          </Section>
        </div>
      </RecordSheet>
      <EditCustomerDialog customer={customer} open={dialog === 'edit'} onClose={() => setDialog(null)} />
      <DeactivateCustomerDialog customer={customer} open={dialog === 'deactivate'} onClose={() => setDialog(null)} />
    </>
  )
}
