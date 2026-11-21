import { etag, permissions } from '@suite/api-client'
import { addDomain, create1, deactivate, rename, set } from '@suite/api-client/platform'
import { appsOptions, appsQueryKey, list1Options } from '@suite/api-client/platform/query'
import { asListQuery, errorMessage, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useTenantList(list: ListState) {
  const { platform } = useApi()
  return useQuery({
    ...list1Options({ client: platform, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.platform.list_1),
  })
}

export function useTenantApps(tenantId: string | undefined) {
  const { platform } = useApi()
  return useQuery({
    ...appsOptions({ client: platform, throwOnError: true, path: { tenantId: tenantId ?? '' } }),
    enabled: Boolean(tenantId),
  })
}

export function useTenantPermissions() {
  return {
    create: usePermission(permissions.platform.create_1),
    rename: usePermission(permissions.platform.rename),
    deactivate: usePermission(permissions.platform.deactivate),
    addDomain: usePermission(permissions.platform.addDomain),
    manageApps: usePermission(permissions.platform.set),
  }
}

export function useCreateTenant(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('tenants')
  return useAction(
    (body: { code: string; name: string; adminEmail: string; adminTemporaryPassword: string }) =>
      create1({ client: platform, throwOnError: true, body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

export function useRenameTenant(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('tenants')
  return useAction(
    (vars: { id: string; version: number; name: string }) =>
      rename({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        body: { name: vars.name },
        headers: { 'If-Match': etag(vars.version) },
      }),
    { onSuccess: onDone, successToast: t('renamed') },
  )
}

export function useDeactivateTenant(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('tenants')
  return useAction(
    (vars: { id: string; version: number }) =>
      deactivate({
        client: platform,
        throwOnError: true,
        path: { id: vars.id },
        headers: { 'If-Match': etag(vars.version) },
      }),
    { onSuccess: onDone, successToast: t('deactivated') },
  )
}

export function useAddDomain(onDone: () => void) {
  const { platform } = useApi()
  const t = useT('tenants')
  return useAction(
    (vars: { id: string; hostname: string }) =>
      addDomain({ client: platform, throwOnError: true, path: { id: vars.id }, body: { hostname: vars.hostname } }),
    { onSuccess: onDone, successToast: t('domainAdded') },
  )
}

export function useSetTenantApp(tenant: { id?: string; code?: string }, onSettled: (appCode: string) => void) {
  const { platform } = useApi()
  const t = useT('tenants')
  return useAction(
    (vars: { appCode: string; appLabel: string; enabled: boolean; version: number }) =>
      set({
        client: platform,
        throwOnError: true,
        path: { tenantId: tenant.id ?? '', appCode: vars.appCode },
        body: { enabled: vars.enabled },
        headers: { 'If-Match': etag(vars.version) },
      }),
    {
      // A toggle moves only this tenant's list of apps, so no other list on screen reloads.
      invalidate: [appsQueryKey({ client: platform, path: { tenantId: tenant.id ?? '' } })],
      onSuccess: (_result, vars) => onSettled(vars.appCode),
      onError: (_error, vars) => onSettled(vars.appCode),
      errorTitle: (error, vars) => t('appFailed', { app: vars.appLabel, message: errorMessage(error) }),
      successToast: (_result, vars) =>
        t(vars.enabled ? 'appEnabled' : 'appDisabled', { app: vars.appLabel, tenant: tenant.code ?? '' }),
    },
  )
}
