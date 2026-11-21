import type { RemoteResponse } from '@suite/api-client/platform'
import { DetailRow, DetailRows, ListRow, Maybe, RecordSheet, RowList, Section, Timestamp } from '@suite/remote-kit'
import { Alert, Button, PermissionGate, StatusBadge } from '@suite/ui'
import { useT } from '../../../i18n'
import type { RemoteState } from '../hooks/use-remotes'

export interface RemoteSheetProps {
  remote: RemoteResponse
  state: RemoteState
  canManage: boolean
  open: boolean
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function RemoteSheet({ remote, state, canManage, open, onClose, onPrev, onNext }: RemoteSheetProps) {
  const t = useT('remotes')
  const tc = useT('common')
  const versions = [...(remote.versions ?? [])].sort((left, right) =>
    (right.registeredAt ?? '').localeCompare(left.registeredAt ?? ''),
  )

  return (
    <RecordSheet
      open={open}
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
            <RowList>
              {versions.map((version) => {
                const active = version.version === remote.activeVersion
                return (
                  <ListRow
                    key={version.version}
                    selected={active}
                    title={
                      <span className="font-mono text-code" title={version.version}>
                        {version.version}
                      </span>
                    }
                    meta={
                      <>
                        {tc('fields.sdkMajor')} {version.sdkMajor} · <Timestamp value={version.registeredAt} />
                      </>
                    }
                    trailing={
                      active ? (
                        <StatusBadge status="ACTIVE">{t('active')}</StatusBadge>
                      ) : (
                        <PermissionGate allowed={canManage}>
                          <Button
                            variant="outline"
                            size="sm"
                            icon="check-circle"
                            disabled={state.isPending}
                            loading={state.busyName === remote.name && state.busyVersion === version.version}
                            onClick={() => version.version && state.activate(remote, version.version)}
                          >
                            {tc('actions.activate')}
                          </Button>
                        </PermissionGate>
                      )
                    }
                  />
                )
              })}
            </RowList>
          )}
        </Section>
      </div>
    </RecordSheet>
  )
}
