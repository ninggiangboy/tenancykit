import type { UserResponse } from '@suite/api-client/platform'
import { DetailRow, DetailRows, Maybe, RecordSheet } from '@suite/remote-kit'
import { Button, CopyId, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useToggleUser, useUserPermissions } from '../hooks/use-users'
import { DirectPermissions } from './direct-permissions'
import { RenameUserDialog } from './rename-user-dialog'

export interface UserSheetProps {
  user: UserResponse
  tenantCode?: string
  open: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function UserSheet({ user, tenantCode, open, onClose, onPrev, onNext }: UserSheetProps) {
  const t = useT('users')
  const tc = useT('common')
  const can = useUserPermissions()
  const toggle = useToggleUser()
  const [renaming, setRenaming] = useState(false)
  const active = user.status === 'ACTIVE'

  return (
    <>
      <RecordSheet
        open={open}
        onClose={onClose}
        eyebrow={t('recordType')}
        title={user.displayName ?? ''}
        description={user.email}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={can.manage}>
            <Button variant="outline" icon="pencil" onClick={() => setRenaming(true)}>
              {tc('actions.rename')}
            </Button>
            <Button
              variant={active ? 'danger' : 'default'}
              icon={active ? 'ban' : 'check-circle'}
              loading={toggle.isPending}
              onClick={() => toggle.mutate(user)}
            >
              {active ? t('disable') : t('enable')}
            </Button>
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={user.status} />
            </DetailRow>
            <DetailRow label={tc('fields.scope')}>{tc(`scope.${user.scope}`)}</DetailRow>
            {user.scope === 'TENANT' && (
              <DetailRow label={tc('fields.tenant')}>
                {tenantCode ? <span className="font-mono">{tenantCode}</span> : <CopyId value={user.tenantId ?? ''} />}
              </DetailRow>
            )}
            <DetailRow label={tc('fields.version')}>
              <Maybe value={user.version} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={user.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <PermissionGate allowed={can.grant}>
            <DirectPermissions user={user} />
          </PermissionGate>
        </div>
      </RecordSheet>
      <RenameUserDialog user={user} open={renaming} onClose={() => setRenaming(false)} />
    </>
  )
}
