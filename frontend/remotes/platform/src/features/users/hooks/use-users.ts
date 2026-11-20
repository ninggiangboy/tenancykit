import { etag, permissions } from '@suite/api-client'
import type { UserResponse } from '@suite/api-client/platform'
import { create, setPermission, update1 } from '@suite/api-client/platform'
import { listOptions } from '@suite/api-client/platform/query'
import { asListQuery, type ListState, useAction, useApi, useToast } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useUserList(list: ListState) {
  const { platform } = useApi()
  return useQuery({
    ...listOptions({ client: platform, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.platform.list),
  })
}

export function useUserPermissions() {
  return {
    create: usePermission(permissions.platform.create),
    manage: usePermission(permissions.platform.update_1),
    grant: usePermission(permissions.platform.setPermission),
  }
}

export function useCreateUser(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('users')
  return useAction(
    (body: { tenantCode?: string; displayName: string; email: string; temporaryPassword: string }) =>
      create({ client: platform, throwOnError: true, body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

type UserAction = 'ENABLE' | 'DISABLE' | 'RENAME'

function updateUser(
  platform: ReturnType<typeof useApi>['platform'],
  id: string,
  version: number,
  action: UserAction,
  name?: string,
) {
  return update1({
    client: platform,
    throwOnError: true,
    path: { id },
    body: { action, name },
    headers: { 'If-Match': etag(version) },
  })
}

export function useRenameUser(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('users')
  return useAction(
    (vars: { user: UserResponse; name: string }) =>
      updateUser(platform, vars.user.id ?? '', vars.user.version ?? 0, 'RENAME', vars.name),
    { onSuccess: onDone, successToast: t('renamed') },
  )
}

/** Enable or disable, with Undo in the toast instead of a confirmation. */
export function useToggleUser() {
  const { platform } = useApi()
  const t = useT('users')
  const tc = useT('common')
  const toast = useToast()
  const queryClient = useQueryClient()
  return useAction(
    (user: UserResponse) =>
      updateUser(platform, user.id ?? '', user.version ?? 0, user.status === 'ACTIVE' ? 'DISABLE' : 'ENABLE'),
    {
      onSuccess: (result, user) => {
        const disabled = user.status === 'ACTIVE'
        toast({
          title: disabled ? t('disabled') : t('enabled'),
          tone: 'success',
          action: {
            label: tc('actions.undo'),
            onClick: async () => {
              const version = result.data.version ?? (user.version ?? 0) + 1
              await updateUser(platform, user.id ?? '', version, disabled ? 'ENABLE' : 'DISABLE')
              await queryClient.invalidateQueries()
            },
          },
        })
      },
    },
  )
}

export function useSetUserPermission() {
  const { platform } = useApi()
  const t = useT('users')
  return useAction(
    (vars: { user: UserResponse; code: string; grant: boolean }) =>
      setPermission({
        client: platform,
        throwOnError: true,
        path: { id: vars.user.id ?? '', code: vars.code },
        query: { grant: vars.grant },
        headers: { 'If-Match': etag(vars.user.version ?? 0) },
      }),
    { successToast: (_result, vars) => (vars.grant ? t('granted') : t('revoked')) },
  )
}
