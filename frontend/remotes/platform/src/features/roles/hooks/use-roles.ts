import { etag, permissions } from '@suite/api-client'
import type { RoleResponse } from '@suite/api-client/platform'
import { assign, create3, rename1, replacePermissions, unassign } from '@suite/api-client/platform'
import { list3Options } from '@suite/api-client/platform/query'
import { asListQuery, type ListState, useAction, useApi, useToast } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useRoleList(list: ListState) {
  const { platform } = useApi()
  return useQuery({
    ...list3Options({ client: platform, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.platform.list_3),
  })
}

export function useCanManageRoles(): boolean {
  return usePermission(permissions.platform.create_3)
}

export function useCreateRole(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('roles')
  return useAction(
    (body: { name: string; permissions: string[] }) => create3({ client: platform, throwOnError: true, body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

export function useRenameRole(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('roles')
  return useAction(
    (vars: { role: RoleResponse; name: string }) =>
      rename1({
        client: platform,
        throwOnError: true,
        path: { id: vars.role.id ?? '' },
        body: { name: vars.name },
        headers: { 'If-Match': etag(vars.role.version ?? 0) },
      }),
    { onSuccess: onDone, successToast: t('renamed') },
  )
}

export function useReplaceRolePermissions() {
  const { platform } = useApi()
  const t = useT('roles')
  return useAction(
    (vars: { role: RoleResponse; permissions: string[] }) =>
      replacePermissions({
        client: platform,
        throwOnError: true,
        path: { id: vars.role.id ?? '' },
        body: { permissions: vars.permissions },
        headers: { 'If-Match': etag(vars.role.version ?? 0) },
      }),
    { successToast: t('saved') },
  )
}

/** Assigns or unassigns, with Undo in the toast. */
export function useAssignRole() {
  const { platform } = useApi()
  const t = useT('roles')
  const tc = useT('common')
  const toast = useToast()
  const queryClient = useQueryClient()
  const call = (id: string, userId: string, on: boolean) =>
    on
      ? assign({ client: platform, throwOnError: true, path: { id, userId } })
      : unassign({ client: platform, throwOnError: true, path: { id, userId } })
  return useAction(
    (vars: { roleId: string; userId: string; assign: boolean }) => call(vars.roleId, vars.userId, vars.assign),
    {
      onSuccess: (_result, vars) =>
        toast({
          title: vars.assign ? t('assigned') : t('unassigned'),
          tone: 'success',
          action: {
            label: tc('actions.undo'),
            onClick: async () => {
              await call(vars.roleId, vars.userId, !vars.assign)
              await queryClient.invalidateQueries()
            },
          },
        }),
    },
  )
}
