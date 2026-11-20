import type { RoleResponse } from '@suite/api-client/platform'
import { Section } from '@suite/remote-kit'
import { UnsavedBar, useHotkey } from '@suite/ui'
import { useState } from 'react'
import { PermissionEditor } from '../../../components/permission-editor'
import { useT } from '../../../i18n'
import { useReplaceRolePermissions } from '../hooks/use-roles'

/** The role's codes; edits collect in the unsaved bar and ⌘S saves them in one replace. */
export function RolePermissions({ role, canManage }: { role: RoleResponse; canManage: boolean }) {
  const t = useT('roles')
  const initial = role.permissions ?? []
  const [value, setValue] = useState(initial)
  const save = useReplaceRolePermissions()
  const before = new Set(initial)
  const after = new Set(value)
  const changed =
    [...after].filter((code) => !before.has(code)).length + [...before].filter((code) => !after.has(code)).length
  const submit = () => {
    if (changed > 0 && !save.isPending) save.mutate({ role, permissions: value })
  }
  useHotkey('mod+s', submit, true)

  return (
    <Section title={t('permissionsTitle')} description={canManage ? t('permissionsDescription') : undefined}>
      <PermissionEditor value={value} onChange={setValue} readOnly={!canManage} />
      {canManage && (
        <UnsavedBar count={changed} saving={save.isPending} onDiscard={() => setValue(initial)} onSave={submit} />
      )}
    </Section>
  )
}
