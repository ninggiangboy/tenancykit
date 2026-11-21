import type { ReservationResponse } from '@suite/api-client/billing'
import {
  DetailRow,
  DetailRows,
  Maybe,
  RecordSheet,
  ResourcePage,
  Timestamp,
  useListState,
  useRecordSelection,
} from '@suite/remote-kit'
import {
  Button,
  CopyId,
  type DataTableColumn,
  Money,
  PermissionGate,
  StatusBadge,
  shortenIds,
  type UrlState,
} from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet } from '../../components/facets'
import { useT } from '../../i18n'
import { CaptureDialog, ReleaseDialog, ReserveDialog } from './components/reservation-dialogs'
import { useCanManageReservations, useReservationList } from './hooks'

const keyOf = (row: ReservationResponse) => row.id ?? ''

export function ReservationsPage({ state }: { state: UrlState }) {
  const t = useT('reservations')
  const tc = useT('common')
  const spec = {
    sortable: ['id'],
    facets: [useEnumFacet('status', 'status', ['HELD', 'CAPTURED', 'RELEASED', 'EXPIRED'])],
  }
  const list = useListState(state, spec)
  const query = useReservationList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const canManage = useCanManageReservations()
  const [dialog, setDialog] = useState<'reserve' | 'capture' | 'release' | null>(null)
  const selected = record.shown

  const columns: DataTableColumn<ReservationResponse>[] = [
    {
      key: 'amount',
      header: tc('fields.amount'),
      mobile: 'primary',
      cardText: (row) => <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />,
      render: (row) => (
        <span className="flex flex-col">
          <Money minor={row.amount?.amount ?? 0} currency={row.amount?.currency} />
          <span className="font-mono text-caption text-muted-foreground" title={row.reference}>
            {shortenIds(row.reference)}
          </span>
        </span>
      ),
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'wallet_id',
      header: tc('fields.wallet'),
      sortable: false,
      mobile: 'hidden',
      render: (row) => <CopyId value={row.walletId ?? ''}>{shortenIds(row.walletId)}</CopyId>,
    },
    {
      key: 'expires_at',
      header: t('expires'),
      sortable: false,
      mobile: 'meta',
      cardText: (row) => row.expiresAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.expiresAt} />,
    },
  ]
  const create = (
    <PermissionGate allowed={canManage}>
      <Button icon="plus" onClick={() => setDialog('reserve')}>
        {t('create')}
      </Button>
    </PermissionGate>
  )

  return (
    <ResourcePage
      title={t('title')}
      description={t('description')}
      primaryAction={create}
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
      emptyAction={create}
    >
      {selected && (
        <>
          <RecordSheet
            open={record.isOpen}
            onClose={record.close}
            onPrev={record.prev}
            onNext={record.next}
            eyebrow={t('title')}
            title={<Money minor={selected.amount?.amount ?? 0} currency={selected.amount?.currency} />}
            description={<StatusBadge status={selected.status} />}
            actions={
              <PermissionGate allowed={canManage && selected.status === 'HELD'}>
                <Button variant="outline" icon="check" onClick={() => setDialog('capture')}>
                  {t('capture')}
                </Button>
                <Button variant="danger" icon="undo" onClick={() => setDialog('release')}>
                  {t('release')}
                </Button>
              </PermissionGate>
            }
          >
            <DetailRows>
              <DetailRow label={t('capturedAmount')}>
                <Money
                  minor={selected.captured?.amount ?? 0}
                  currency={selected.captured?.currency ?? selected.amount?.currency}
                />
              </DetailRow>
              <DetailRow label={tc('fields.wallet')}>
                <CopyId value={selected.walletId ?? ''} />
              </DetailRow>
              <DetailRow label={tc('fields.reference')}>
                <Maybe value={selected.reference} />
              </DetailRow>
              <DetailRow label={t('expires')}>
                <Timestamp value={selected.expiresAt} />
              </DetailRow>
              <DetailRow label={tc('fields.id')}>
                <CopyId value={selected.id ?? ''} />
              </DetailRow>
            </DetailRows>
          </RecordSheet>
          <CaptureDialog reservation={selected} open={dialog === 'capture'} onClose={() => setDialog(null)} />
          <ReleaseDialog reservation={selected} open={dialog === 'release'} onClose={() => setDialog(null)} />
        </>
      )}
      <ReserveDialog open={dialog === 'reserve'} onClose={() => setDialog(null)} />
    </ResourcePage>
  )
}
