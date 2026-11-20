import type { CouponResponse } from '@suite/api-client/billing'
import { ResourcePage, Timestamp, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, type DataTableColumn, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet } from '../../components/facets'
import { useT } from '../../i18n'
import { CouponBenefit } from './components/coupon-benefit'
import { CouponSheet } from './components/coupon-sheet'
import { CreateCouponDialog } from './components/create-coupon-dialog'
import { useCanManageCoupons, useCouponList } from './hooks/use-coupons'

const keyOf = (row: CouponResponse) => row.id ?? ''

export function CouponsPage({ state }: { state: UrlState }) {
  const t = useT('coupons')
  const tc = useT('common')
  const spec = {
    sortable: ['name', 'created_at', 'id'],
    facets: [useEnumFacet('status', 'status', ['ACTIVE', 'DEACTIVATED'])],
  }
  const list = useListState(state, spec)
  const query = useCouponList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const canManage = useCanManageCoupons()
  const [creating, setCreating] = useState(false)

  const columns: DataTableColumn<CouponResponse>[] = [
    {
      key: 'name',
      header: tc('fields.name'),
      mobile: 'primary',
      cardText: (row) => row.name,
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.name}</span>
          <span className="text-caption text-muted-foreground">
            <CouponBenefit coupon={row} />
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
      key: 'redemptions',
      header: t('redemptions'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => (
        <span className="tabular-nums">
          {row.redemptionsCount ?? 0}/{row.maxRedemptions ?? t('unlimited')}
        </span>
      ),
    },
    {
      key: 'created_at',
      header: tc('fields.created'),
      mobile: 'meta',
      cardText: (row) => row.createdAt?.slice(0, 10),
      render: (row) => <Timestamp value={row.createdAt} />,
    },
  ]
  const create = (
    <PermissionGate allowed={canManage}>
      <Button icon="plus" onClick={() => setCreating(true)}>
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
      error={query.error}
      refetch={() => void query.refetch()}
      columns={columns}
      rowKey={keyOf}
      onRowOpen={(row) => record.open(keyOf(row))}
      emptyTitle={t('emptyTitle')}
      emptyHint={t('emptyHint')}
      emptyAction={create}
    >
      {record.selected && (
        <CouponSheet coupon={record.selected} onClose={record.close} onPrev={record.prev} onNext={record.next} />
      )}
      <CreateCouponDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
