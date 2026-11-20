import { permissions } from '@suite/api-client'
import type { SystemResponse } from '@suite/api-client/platform'
import { create2, createCredential, revoke, rotate, update } from '@suite/api-client/platform'
import { list2Options, listCredentialsOptions } from '@suite/api-client/platform/query'
import { asListQuery, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useSystemList(list: ListState) {
  const { platform } = useApi()
  return useQuery({
    ...list2Options({ client: platform, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.platform.list_2),
  })
}

export function useSystemPermissions() {
  return {
    create: usePermission(permissions.platform.create_2),
    rename: usePermission(permissions.platform.update),
    credentials: usePermission(permissions.platform.listCredentials),
  }
}

export function useCredentials(system: SystemResponse, enabled: boolean) {
  const { platform } = useApi()
  return useQuery({
    ...listCredentialsOptions({ client: platform, throwOnError: true, path: { id: system.id ?? '' } }),
    enabled: Boolean(system.id) && enabled,
  })
}

export function useCreateSystem(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('systems')
  return useAction(
    (body: { name: string; purpose: string }) => create2({ client: platform, throwOnError: true, body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

export function useUpdateSystem(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('systems')
  return useAction(
    (vars: { id: string; action: 'RENAME' | 'DISABLE'; name?: string }) =>
      update({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { action: vars.action, name: vars.name },
      }),
    { onSuccess: onDone, successToast: (_result, vars) => (vars.action === 'RENAME' ? t('renamed') : t('disabled')) },
  )
}

/** Issue and rotate both answer with a key shown once; it goes to `onSecret`, never to the query cache. */
export function useIssueCredential(system: SystemResponse, onSecret: (key: string | null) => void) {
  const { platform } = useApi()
  const t = useT('systems')
  return useAction(
    (body: { label: string; scopes: string[] }) =>
      createCredential({ client: platform, throwOnError: true, path: { id: system.id ?? '' }, body }),
    { onSuccess: (result) => onSecret(result.data.key ?? null), successToast: t('credentialCreated') },
  )
}

export function useRotateCredential(system: SystemResponse, onSecret: (key: string | null) => void) {
  const { platform } = useApi()
  const t = useT('systems')
  return useAction(
    (overlapMinutes: number) =>
      rotate({ client: platform, throwOnError: true, path: { id: system.id ?? '' }, body: { overlapMinutes } }),
    { onSuccess: (result) => onSecret(result.data.key ?? null), successToast: t('rotated') },
  )
}

export function useRevokeCredential(system: SystemResponse, onDone: () => void) {
  const { platform } = useApi()
  const t = useT('systems')
  return useAction(
    (credentialId: string) =>
      revoke({ client: platform, throwOnError: true, path: { systemId: system.id ?? '', credentialId } }),
    { onSuccess: onDone, successToast: t('revoked') },
  )
}
