import { listQuery, permissions } from '@suite/api-client'
import { createEndpoint, resend, rotateSecret, updateEndpoint } from '@suite/api-client/billing'
import { listDeliveriesOptions, listEndpointsOptions } from '@suite/api-client/billing/query'
import { asListQuery, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

export function useEndpointList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...listEndpointsOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.billing.listEndpoints),
  })
}

export function useDeliveries(endpointId: string | undefined) {
  const { billing } = useApi()
  return useQuery({
    ...listDeliveriesOptions({
      client: billing,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: [{ field: 'endpoint_id', op: 'eq', value: endpointId ?? '' }],
          sort: '-created_at',
          limit: 50,
        }),
      ),
    }),
    enabled: Boolean(endpointId),
  })
}

export function useCanManageWebhooks(): boolean {
  return usePermission(permissions.billing.createEndpoint)
}

/** Creating and rotating both answer with the signing secret, which goes to `onSecret` and nowhere else. */
export function useCreateEndpoint(onSecret: (secret: string | null) => void) {
  const { billing } = useApi()
  const t = useT('webhooks')
  return useAction(
    (body: { url: string; description?: string; eventTypes: string[] }) =>
      createEndpoint({ client: billing, throwOnError: true, body }),
    { onSuccess: (result) => onSecret(result.data.signingSecret ?? null), successToast: t('created') },
  )
}

export function useRotateSecret(onSecret: (secret: string | null) => void) {
  const { billing } = useApi()
  const t = useT('webhooks')
  return useAction((id: string) => rotateSecret({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: (result) => onSecret(result.data.signingSecret ?? null),
    successToast: t('rotated'),
  })
}

export function useSetEndpointStatus() {
  const { billing } = useApi()
  const t = useT('webhooks')
  return useAction(
    (vars: { id: string; status: 'ACTIVE' | 'DISABLED' }) =>
      updateEndpoint({ client: billing, throwOnError: true, path: { id: vars.id }, body: { status: vars.status } }),
    { successToast: t('updated') },
  )
}

export function useResendDelivery(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('webhooks')
  return useAction((id: string) => resend({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('resent'),
  })
}
