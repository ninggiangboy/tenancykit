import type { RemoteResponse } from '@suite/api-client/platform'
import { listRemotesOptions } from '@suite/api-client/platform/query'
import { asListQuery, ResourcePage, useApi, useListState, useRecordSelection, useViewCounts } from '@suite/remote-kit'
import type { UrlState } from '@suite/ui'
import { useT } from '../../i18n'
import { useRemoteColumns } from './columns'
import { RemoteSheet } from './components/remote-sheet'
import { useCanManageRemotes, useRemoteList, useRemoteState } from './hooks/use-remotes'
import { useRemoteListSpec } from './list-spec'

const keyOf = (row: RemoteResponse) => row.name ?? ''

export function RemotesPage({ state }: { state: UrlState }) {
  const t = useT('remotes')
  const spec = useRemoteListSpec()
  const list = useListState(state, spec)
  const query = useRemoteList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const canManage = useCanManageRemotes()
  const remoteState = useRemoteState()
  const columns = useRemoteColumns(remoteState, canManage)
  const { platform } = useApi()
  const viewCounts = useViewCounts(spec.views, (params) =>
    listRemotesOptions({ client: platform, throwOnError: true, query: asListQuery(params) }),
  )

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      viewCounts={viewCounts}
      spec={spec}
      list={list}
      rows={rows}
      page={query.data?.page}
      loading={query.isLoading}
      refreshing={query.isFetching && !query.isLoading}
      error={query.error}
      refetch={() => void query.refetch()}
      columns={columns}
      rowKey={keyOf}
      onRowOpen={(row) => record.open(keyOf(row))}
      emptyTitle={t('emptyTitle')}
      emptyHint={t('emptyHint')}
    >
      {record.shown && (
        <RemoteSheet
          open={record.isOpen}
          remote={record.shown}
          state={remoteState}
          canManage={canManage}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
        />
      )}
    </ResourcePage>
  )
}
