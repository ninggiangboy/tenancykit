import type { RoleResponse } from '@suite/api-client/platform'
import { DetailRow, DetailRows, Maybe, RecordSheet } from '@suite/remote-kit'
import { Button, CopyId, PermissionGate } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useCanManageRoles } from '../hooks/use-roles'
import { AssignUser } from './assign-user'
import { RenameRoleDialog } from './role-dialogs'
import { RolePermissions } from './role-permissions'

export interface RoleSheetProps {
  role: RoleResponse
  tenantCode?: string
  open: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function RoleSheet({ role, tenantCode, open, onClose, onPrev, onNext }: RoleSheetProps) {
  const t = useT('roles')
  const tc = useT('common')
  const canManage = useCanManageRoles()
  const [renaming, setRenaming] = useState(false)
  const scope = tc(`scope.${role.scope}`)

  return (
    <>
      <RecordSheet
        open={open}
        onClose={onClose}
        eyebrow={t('recordType')}
        title={role.name ?? ''}
        description={role.scope === 'TENANT' && tenantCode ? `${scope} · ${tenantCode}` : scope}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={canManage && !role.system}>
            <Button variant="outline" icon="pencil" onClick={() => setRenaming(true)}>
              {tc('actions.rename')}
            </Button>
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.scope')}>{scope}</DetailRow>
            {role.scope === 'TENANT' && (
              <DetailRow label={tc('fields.tenant')}>
                {tenantCode ? <span className="font-mono">{tenantCode}</span> : <CopyId value={role.tenantId ?? ''} />}
              </DetailRow>
            )}
            <DetailRow label={tc('fields.version')}>
              <Maybe value={role.version} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={role.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <RolePermissions key={`${role.id}:${role.version}`} role={role} canManage={canManage} />
          <PermissionGate allowed={canManage}>
            <AssignUser roleId={role.id ?? ''} />
          </PermissionGate>
        </div>
      </RecordSheet>
      <RenameRoleDialog role={role} open={renaming} onClose={() => setRenaming(false)} />
    </>
  )
}
