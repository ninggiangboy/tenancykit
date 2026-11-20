import { listQuery, permissions } from '@suite/api-client'
import {
  connectDirect,
  disconnect,
  reconcile,
  refresh,
  replace as replaceReturnUrls,
  resolveIncident,
  retry,
  setWebhookSecret,
  startConnect,
} from '@suite/api-client/billing'
import { eventsOptions, getConfigurationOptions, incidentsOptions, listOptions } from '@suite/api-client/billing/query'
import { asListQuery, errorStatus, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

const latest = asListQuery(listQuery({ sort: '-created_at', limit: 20 }))

/** The connection; a tenant that never connected gets 404, which is a state here, not an error. */
export function useStripeConfiguration() {
  const { billing } = useApi()
  const query = useQuery({ ...getConfigurationOptions({ client: billing, throwOnError: true }), retry: false })
  const missing = errorStatus(query.error) === 404
  return { ...query, connected: Boolean(query.data) && !missing, missing, failure: missing ? null : query.error }
}

export function useStripeEvents(enabled: boolean) {
  const { billing } = useApi()
  return useQuery({ ...eventsOptions({ client: billing, throwOnError: true, query: latest }), enabled })
}

export function useStripeIncidents(enabled: boolean) {
  const { billing } = useApi()
  return useQuery({ ...incidentsOptions({ client: billing, throwOnError: true, query: latest }), enabled })
}

export function useReturnUrls() {
  const { billing } = useApi()
  return useQuery({ ...listOptions({ client: billing, throwOnError: true }) })
}

export function useCanManageStripe(): boolean {
  return usePermission(permissions.billing.startConnect)
}

export function useStripeActions() {
  const { billing } = useApi()
  const t = useT('stripe')
  return {
    start: useAction(
      (body: { displayName: string; contactEmail: string }) =>
        startConnect({ client: billing, throwOnError: true, body }).then((answer) => answer.data),
      { successToast: t('connected') },
    ),
    direct: useAction(
      (body: { secretKey: string; publishableKey: string; webhookSecret?: string }) =>
        connectDirect({ client: billing, throwOnError: true, body }),
      { successToast: t('connected') },
    ),
    webhookSecret: useAction(
      (webhookSecret: string) => setWebhookSecret({ client: billing, throwOnError: true, body: { webhookSecret } }),
      {
        successToast: t('saved'),
      },
    ),
    disconnect: useAction(() => disconnect({ client: billing, throwOnError: true }), {
      successToast: t('disconnected'),
    }),
    refresh: useAction(() => refresh({ client: billing, throwOnError: true }), { successToast: t('refreshed') }),
    reconcile: useAction(() => reconcile({ client: billing, throwOnError: true }).then((answer) => answer.data), {
      successToast: t('reconciled'),
    }),
    returnUrls: useAction(
      (urls: string[]) => replaceReturnUrls({ client: billing, throwOnError: true, body: { urls } }),
      {
        successToast: t('saved'),
      },
    ),
    retry: useAction((id: string) => retry({ client: billing, throwOnError: true, path: { id } }), {
      successToast: t('retried'),
    }),
    resolve: useAction((id: string) => resolveIncident({ client: billing, throwOnError: true, path: { id } }), {
      successToast: t('resolved'),
    }),
  }
}

export type StripeActions = ReturnType<typeof useStripeActions>
