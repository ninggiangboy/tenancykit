import type { TenantResponse } from '@suite/api-client/platform'
import { DetailRow, DetailRows, IdValue, RecordSheet, Timestamp } from '@suite/remote-kit'
import { Button, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useTenantPermissions } from '../hooks/use-tenants'
import { AddDomainDialog, DeactivateTenantDialog, RenameTenantDialog } from './tenant-action-dialogs'
import { TenantApps } from './tenant-apps'

export interface TenantSheetProps {
  tenant: TenantResponse
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function TenantSheet({ tenant, onClose, onPrev, onNext }: TenantSheetProps) {
  const t = useT('tenants')
  const tc = useT('common')
  const can = useTenantPermissions()
  const [dialog, setDialog] = useState<'rename' | 'domain' | 'deactivate' | null>(null)
  const close = () => setDialog(null)
  const active = tenant.status !== 'DEACTIVATED'

  return (
    <>
      <RecordSheet
        open
        onClose={onClose}
        eyebrow={t('recordType')}
        title={tenant.name ?? tenant.code ?? ''}
        description={<span className="font-mono">{tenant.code}</span>}
        onPrev={onPrev}
        onNext={onNext}
        footer={
          <>
            <PermissionGate allowed={can.rename}>
              <Button variant="outline" size="sm" icon="settings" onClick={() => setDialog('rename')}>
                {tc('actions.rename')}
              </Button>
            </PermissionGate>
            <PermissionGate allowed={can.addDomain}>
              <Button variant="outline" size="sm" icon="plus" onClick={() => setDialog('domain')}>
                {tc('actions.addDomain')}
              </Button>
            </PermissionGate>
            <PermissionGate allowed={can.deactivate && active}>
              <Button variant="destructive" size="sm" icon="ban" onClick={() => setDialog('deactivate')}>
                {tc('actions.deactivate')}
              </Button>
            </PermissionGate>
          </>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={tenant.status} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={tenant.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <IdValue value={tenant.id} />
            </DetailRow>
          </DetailRows>
          <TenantApps tenant={tenant} canManage={can.manageApps} />
        </div>
      </RecordSheet>
      <RenameTenantDialog tenant={tenant} open={dialog === 'rename'} onClose={close} />
      <AddDomainDialog tenant={tenant} open={dialog === 'domain'} onClose={close} />
      <DeactivateTenantDialog tenant={tenant} open={dialog === 'deactivate'} onClose={close} />
    </>
  )
}
