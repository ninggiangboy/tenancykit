import { etag, permissions } from '@suite/api-client'
import type { RemoteResponse, StateRequest } from '@suite/api-client/platform'
import { setState } from '@suite/api-client/platform'
import { listRemotesOptions } from '@suite/api-client/platform/query'
import { asListQuery, type ListState, useAction, useApi, useToast } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useT } from '../../../i18n'

export function useRemoteList(list: ListState) {
  const { platform } = useApi()
  return useQuery({
    ...listRemotesOptions({ client: platform, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.platform.listRemotes),
  })
}

export function useCanManageRemotes(): boolean {
  return usePermission(permissions.platform.setState)
}

interface StateChange {
  remote: RemoteResponse
  body: StateRequest
}

/**
 * Enable, disable or activate a version. The switch shows the new state at once; the toast offers Undo, which writes
 * the previous state back against the version the server just returned.
 */
export function useRemoteState() {
  const { platform } = useApi()
  const t = useT('remotes')
  const tc = useT('common')
  const toast = useToast()
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<Record<string, boolean>>({})
  const settle = (name: string) =>
    setPending((current) => {
      const next = { ...current }
      delete next[name]
      return next
    })
  const write = (name: string, body: StateRequest, version: number) =>
    setState({ client: platform, throwOnError: true, path: { name }, body, headers: { 'If-Match': etag(version) } })
  const undo = async (name: string, body: StateRequest, version: number) => {
    await write(name, body, version)
    await queryClient.invalidateQueries()
  }

  const mutation = useAction(({ remote, body }: StateChange) => write(remote.name ?? '', body, remote.version ?? 0), {
    onSuccess: (result, { remote, body }) => {
      const name = remote.name ?? ''
      settle(name)
      const version = result.data.version ?? (remote.version ?? 0) + 1
      if (body.activeVersion) {
        toast({
          title: t('activated', { name, version: body.activeVersion }),
          tone: 'success',
          action: remote.activeVersion
            ? {
                label: tc('actions.undo'),
                onClick: () =>
                  void undo(
                    name,
                    { enabled: result.data.enabled ?? false, activeVersion: remote.activeVersion },
                    version,
                  ),
              }
            : undefined,
        })
        return
      }
      toast({
        title: body.enabled ? t('enabled') : t('disabled'),
        tone: 'success',
        action: {
          label: tc('actions.undo'),
          onClick: () => void undo(name, { enabled: remote.enabled ?? false }, version),
        },
      })
    },
    onError: (_error, { remote }) => settle(remote.name ?? ''),
  })

  return {
    isPending: mutation.isPending,
    busyName: mutation.isPending ? mutation.variables?.remote.name : undefined,
    enabledOf: (remote: RemoteResponse) => pending[remote.name ?? ''] ?? remote.enabled ?? false,
    toggle: (remote: RemoteResponse, enabled: boolean) => {
      setPending((current) => ({ ...current, [remote.name ?? '']: enabled }))
      mutation.mutate({ remote, body: { enabled } })
    },
    // Activating keeps whatever the remote is now; enabling is its own write.
    activate: (remote: RemoteResponse, activeVersion: string) =>
      mutation.mutate({ remote, body: { enabled: remote.enabled ?? false, activeVersion } }),
  }
}

export type RemoteState = ReturnType<typeof useRemoteState>
