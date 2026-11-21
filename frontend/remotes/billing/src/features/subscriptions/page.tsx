import type { SubscriptionResponse } from '@suite/api-client/billing'
import { ResourcePage, Timestamp, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, type DataTableColumn, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet, useEnumLabel } from '../../components/facets'
import { CustomerCell, useCustomerNames } from '../../components/lookups'
import { useT } from '../../i18n'
import { SubscribeDialog } from './components/subscribe-dialog'
import { SubscriptionSheet } from './components/subscription-sheet'
import { useCanManageSubscriptions, useSubscriptionList } from './hooks/use-subscriptions'

const keyOf = (row: SubscriptionResponse) => row.id ?? ''

export function SubscriptionsPage({ state }: { state: UrlState }) {
  const t = useT('subscriptions')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const spec = { sortable: ['id'], facets: [useEnumFacet('status', 'status', ['ACTIVE', 'PAST_DUE', 'CANCELED'])] }
  const list = useListState(state, spec)
  const query = useSubscriptionList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const names = useCustomerNames(rows.map((row) => row.customerId))
  const canManage = useCanManageSubscriptions()
  const [creating, setCreating] = useState(false)

  const columns: DataTableColumn<SubscriptionResponse>[] = [
    {
      key: 'plan_code',
      header: tc('fields.plan'),
      mobile: 'primary',
      cardText: (row) => row.planCode,
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.planCode}</span>
          <span className="text-caption text-muted-foreground">{row.cycle ? enumLabel(row.cycle) : ''}</span>
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
      key: 'customer_id',
      header: tc('fields.customer'),
      mobile: 'secondary',
      render: (row) => <CustomerCell id={row.customerId} names={names} />,
    },
    {
      key: 'current_period_end',
      header: t('nextRenewal'),
      mobile: 'meta',
      cardText: (row) => row.currentPeriodEnd?.slice(0, 10),
      render: (row) => <Timestamp value={row.currentPeriodEnd} />,
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
      {record.shown && (
        <SubscriptionSheet
          open={record.isOpen}
          subscription={record.shown}
          names={names}
          onClose={record.close}
          onPrev={record.prev}
          onNext={record.next}
        />
      )}
      <SubscribeDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
