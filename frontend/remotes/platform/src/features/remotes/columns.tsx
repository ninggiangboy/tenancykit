import type { RemoteResponse } from '@suite/api-client/platform'
import { Maybe } from '@suite/remote-kit'
import { AppMark, type DataTableColumn, PermissionGate, Switch } from '@suite/ui'
import { useT } from '../../i18n'
import type { RemoteState } from './hooks/use-remotes'

export function useRemoteColumns(state: RemoteState, canManage: boolean): DataTableColumn<RemoteResponse>[] {
  const t = useT('remotes')
  const tc = useT('common')
  return [
    {
      key: 'name',
      header: tc('fields.name'),
      mobile: 'primary',
      render: (row) => (
        <span className="flex items-center gap-2">
          <AppMark app={row.appCode ?? row.name ?? ''} size="sm" />
          <span className="flex flex-col">
            <span className="text-body-strong text-foreground">{row.title ?? row.name}</span>
            <span className="font-mono text-caption text-muted-foreground">{row.name}</span>
          </span>
        </span>
      ),
    },
    {
      key: 'audiences',
      header: tc('fields.audiences'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => (
        <span className="text-small">{row.audiences?.map((scope) => tc(`scope.${scope}`)).join(', ')}</span>
      ),
    },
    {
      key: 'activeVersion',
      header: tc('fields.activeVersion'),
      sortable: false,
      mobile: 'hidden',
      render: (row) => (
        <span className="block max-w-56 truncate font-mono text-code" title={row.activeVersion}>
          <Maybe value={row.activeVersion} />
        </span>
      ),
    },
    {
      key: 'versions',
      header: t('versions'),
      sortable: false,
      mobile: 'meta',
      render: (row) => t('versionCount', { count: row.versions?.length ?? 0 }),
    },
    {
      key: 'enabled',
      header: tc('fields.enabled'),
      sortable: false,
      align: 'end',
      mobile: 'badge',
      render: (row) => (
        <PermissionGate allowed={canManage}>
          {/* biome-ignore lint/a11y/noStaticElementInteractions: keeps a switch click from also opening the row's sheet. */}
          <span onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
            <Switch
              aria-label={t('enable')}
              checked={state.enabledOf(row)}
              busy={state.busyName === row.name}
              onCheckedChange={(checked) => state.toggle(row, checked)}
            />
          </span>
        </PermissionGate>
      ),
    },
  ]
}
