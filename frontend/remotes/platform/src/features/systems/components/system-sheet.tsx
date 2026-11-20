import type { SystemResponse } from '@suite/api-client/platform'
import { DetailRow, DetailRows, Maybe, RecordSheet, Timestamp } from '@suite/remote-kit'
import { Button, CopyId, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useSystemPermissions } from '../hooks/use-systems'
import { Credentials } from './credentials'
import { DisableSystemDialog, RenameSystemDialog } from './system-dialogs'

export interface SystemSheetProps {
  system: SystemResponse
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function SystemSheet({ system, onClose, onPrev, onNext }: SystemSheetProps) {
  const t = useT('systems')
  const tc = useT('common')
  const can = useSystemPermissions()
  const [dialog, setDialog] = useState<'rename' | 'disable' | null>(null)

  return (
    <>
      <RecordSheet
        open
        onClose={onClose}
        eyebrow={t('recordType')}
        title={system.name ?? ''}
        description={system.purpose}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <>
            <PermissionGate allowed={can.rename}>
              <Button variant="outline" icon="settings" onClick={() => setDialog('rename')}>
                {tc('actions.rename')}
              </Button>
            </PermissionGate>
            <PermissionGate allowed={can.create && system.status !== 'DISABLED'}>
              <Button variant="danger" icon="ban" onClick={() => setDialog('disable')}>
                {t('disable')}
              </Button>
            </PermissionGate>
          </>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={system.status} />
            </DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={system.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.version')}>
              <Maybe value={system.version} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={system.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <Credentials system={system} canManage={can.credentials} />
        </div>
      </RecordSheet>
      <RenameSystemDialog system={system} open={dialog === 'rename'} onClose={() => setDialog(null)} />
      <DisableSystemDialog system={system} open={dialog === 'disable'} onClose={() => setDialog(null)} />
    </>
  )
}
