import type { RemoteResponse } from '@suite/api-client/platform'
import { DetailRow, DetailRows, Maybe, RecordSheet, Section, Timestamp } from '@suite/remote-kit'
import { Alert, Button, PermissionGate, StatusBadge } from '@suite/ui'
import { useT } from '../../../i18n'
import type { RemoteState } from '../hooks/use-remotes'

export interface RemoteSheetProps {
  remote: RemoteResponse
  state: RemoteState
  canManage: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function RemoteSheet({ remote, state, canManage, onClose, onPrev, onNext }: RemoteSheetProps) {
  const t = useT('remotes')
  const tc = useT('common')
  const versions = [...(remote.versions ?? [])].sort((left, right) =>
    (right.registeredAt ?? '').localeCompare(left.registeredAt ?? ''),
  )

  return (
    <RecordSheet
      open
      onClose={onClose}
      eyebrow={t('recordType')}
      title={remote.title ?? remote.name ?? ''}
      description={<span className="font-mono">{remote.name}</span>}
      onPrev={onPrev}
      onNext={onNext}
    >
      <div className="flex flex-col gap-5">
        <DetailRows>
          <DetailRow label={tc('fields.enabled')}>
            <StatusBadge tone={remote.enabled ? 'success' : 'neutral'}>
              {remote.enabled ? tc('state.on') : tc('state.off')}
            </StatusBadge>
          </DetailRow>
          <DetailRow label={tc('fields.appCode')}>
            <span className="font-mono">{remote.appCode}</span>
          </DetailRow>
          <DetailRow label={tc('fields.audiences')}>
            {remote.audiences?.map((scope) => tc(`scope.${scope}`)).join(', ')}
          </DetailRow>
          <DetailRow label={tc('fields.activeVersion')}>
            <span className="font-mono text-code">
              <Maybe value={remote.activeVersion} />
            </span>
          </DetailRow>
          <DetailRow label={tc('fields.created')}>
            <Timestamp value={remote.createdAt} />
          </DetailRow>
        </DetailRows>
        <Section title={t('versions')} description={t('activateHint')}>
          {versions.length === 0 ? (
            <Alert tone="info" title={t('emptyTitle')}>
              {t('emptyHint')}
            </Alert>
          ) : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {versions.map((version) => {
                const active = version.version === remote.activeVersion
                return (
                  <li key={version.version} className="flex items-center justify-between gap-3 px-3 py-2">
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className="flex items-center gap-2">
                        <span className="truncate font-mono text-code text-foreground" title={version.version}>
                          {version.version}
                        </span>
                        {active && <StatusBadge status="ACTIVE">{t('active')}</StatusBadge>}
                      </span>
                      <span className="text-caption text-muted-foreground">
                        {tc('fields.sdkMajor')} {version.sdkMajor} · <Timestamp value={version.registeredAt} />
                      </span>
                    </span>
                    {!active && (
                      <PermissionGate allowed={canManage}>
                        <Button
                          variant="outline"
                          size="sm"
                          icon="refresh"
                          disabled={state.isPending}
                          onClick={() => version.version && state.activate(remote, version.version)}
                        >
                          {tc('actions.activate')}
                        </Button>
                      </PermissionGate>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </Section>
      </div>
    </RecordSheet>
  )
}
