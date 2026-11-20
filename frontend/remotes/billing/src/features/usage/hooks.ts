import { permissions } from '@suite/api-client'
import type { UsageEventRequest } from '@suite/api-client/billing'
import { aggregates, recordBatch, recordOne } from '@suite/api-client/billing'
import { historyOptions } from '@suite/api-client/billing/query'
import { asListQuery, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

export function useUsageHistory(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...historyOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.history),
  })
}

export function useCanRecordUsage(): boolean {
  return usePermission(permissions.billing.recordOne)
}

export function useRecordUsage(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('usage')
  return useAction((body: UsageEventRequest) => recordOne({ client: billing, throwOnError: true, body }), {
    onSuccess: onDone,
    successToast: t('recorded'),
  })
}

export function useRecordBatch() {
  const { billing } = useApi()
  const t = useT('usage')
  return useAction(
    (events: UsageEventRequest[]) =>
      recordBatch({ client: billing, throwOnError: true, body: { events } }).then((answer) => answer.data),
    { successToast: t('batchRecorded') },
  )
}

export function useAggregates() {
  const { billing } = useApi()
  return useMutation({
    mutationFn: async (query: { customerId: string; from: string; to: string; metric?: string }) =>
      (
        await aggregates({
          client: billing,
          throwOnError: true,
          query: {
            customerId: query.customerId,
            from: query.from,
            to: query.to,
            metric: query.metric ? [query.metric] : undefined,
          },
        })
      ).data,
  })
}
