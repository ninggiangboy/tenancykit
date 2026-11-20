import { permissions } from '@suite/api-client'
import type { CreatePlanRequest, VersionRequest } from '@suite/api-client/billing'
import { createPlan, publishVersion, retire } from '@suite/api-client/billing'
import { listPlansOptions } from '@suite/api-client/billing/query'
import { asListQuery, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function usePlanList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...listPlansOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.listPlans),
  })
}

export function useCanManagePlans(): boolean {
  return usePermission(permissions.billing.createPlan)
}

export function useCreatePlan(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('plans')
  return useAction((body: CreatePlanRequest) => createPlan({ client: billing, throwOnError: true, body }), {
    onSuccess: onDone,
    successToast: t('created'),
  })
}

export function usePublishVersion(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('plans')
  return useAction(
    (vars: { id: string; body: VersionRequest }) =>
      publishVersion({ client: billing, throwOnError: true, path: { id: vars.id }, body: vars.body }),
    { onSuccess: onDone, successToast: t('versionPublished') },
  )
}

export function useRetirePlan(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('plans')
  return useAction((id: string) => retire({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('retired'),
  })
}
