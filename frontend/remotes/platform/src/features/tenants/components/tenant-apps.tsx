import type { TenantResponse } from '@suite/api-client/platform'
import { errorMessage, ListRow, RowList, Section, useKitT } from '@suite/remote-kit'
import { Alert, AppMark, humanize, PermissionGate, Skeleton, Switch } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useSetTenantApp, useTenantApps } from '../hooks/use-tenants'

/** The tenant's app entitlements; a switch flips at once and settles when the server answers. */
export function TenantApps({ tenant, canManage }: { tenant: TenantResponse; canManage: boolean }) {
  const t = useT('tenants')
  const tk = useKitT()
  const query = useTenantApps(tenant.id)
  const [pending, setPending] = useState<Record<string, boolean>>({})
  const save = useSetTenantApp(tenant, (appCode) =>
    setPending((current) => {
      const next = { ...current }
      delete next[appCode]
      return next
    }),
  )
  const apps = query.data ?? []

  return (
    <Section title={t('appsTitle')} description={t('appsDescription')}>
      {query.error ? (
        <Alert tone="danger" title={tk('state.error')}>
          {errorMessage(query.error)}
        </Alert>
      ) : query.isLoading ? (
        <Skeleton width="100%" height={120} />
      ) : apps.length === 0 ? (
        <p className="text-small text-muted-foreground">{t('noApps')}</p>
      ) : (
        <RowList>
          {apps.map((app) => {
            const appCode = app.appCode ?? ''
            const appLabel = humanize(appCode.toUpperCase())
            const enabled = pending[appCode] ?? app.enabled ?? false
            return (
              <ListRow
                key={appCode}
                lead={<AppMark app={appCode} size="sm" />}
                title={<span className="text-body-strong">{appLabel}</span>}
                meta={<span className="font-mono">{appCode}</span>}
                trailing={
                  <PermissionGate allowed={canManage}>
                    <Switch
                      aria-label={appLabel}
                      checked={enabled}
                      busy={save.isPending && save.variables?.appCode === appCode}
                      onCheckedChange={(checked) => {
                        setPending((current) => ({ ...current, [appCode]: checked }))
                        save.mutate({ appCode, appLabel, enabled: checked, version: app.version ?? 0 })
                      }}
                    />
                  </PermissionGate>
                }
              />
            )
          })}
        </RowList>
      )}
    </Section>
  )
}
