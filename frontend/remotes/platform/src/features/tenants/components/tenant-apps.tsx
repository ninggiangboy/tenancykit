import type { TenantResponse } from '@suite/api-client/platform'
import { errorMessage, Section, useKitT } from '@suite/remote-kit'
import { Alert, AppMark, humanize, PermissionGate, Skeleton, StatusBadge, Switch } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useSetTenantApp, useTenantApps } from '../hooks/use-tenants'

/** The tenant's app entitlements; a switch flips at once and settles when the server answers. */
export function TenantApps({ tenant, canManage }: { tenant: TenantResponse; canManage: boolean }) {
  const t = useT('tenants')
  const tc = useT('common')
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
        <ul className="divide-y divide-border rounded-lg border border-border">
          {apps.map((app) => {
            const appCode = app.appCode ?? ''
            const appLabel = humanize(appCode.toUpperCase())
            const enabled = pending[appCode] ?? app.enabled ?? false
            return (
              <li key={appCode} className="flex items-center justify-between gap-3 px-3 py-2">
                <span className="flex min-w-0 items-center gap-2">
                  <AppMark app={appCode} size="sm" />
                  <span className="flex min-w-0 flex-col">
                    <span className="truncate text-body-strong text-foreground">{appLabel}</span>
                    <span className="truncate font-mono text-caption text-muted-foreground">{appCode}</span>
                  </span>
                </span>
                <span className="flex shrink-0 items-center gap-3">
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
                  <StatusBadge tone={enabled ? 'success' : 'neutral'}>
                    {enabled ? tc('state.on') : tc('state.off')}
                  </StatusBadge>
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </Section>
  )
}
