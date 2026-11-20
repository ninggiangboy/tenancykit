import type { PlanResponse } from '@suite/api-client/billing'
import { ResourcePage, useListState, useRecordSelection } from '@suite/remote-kit'
import { Button, type DataTableColumn, PermissionGate, StatusBadge, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useEnumFacet, useEnumLabel } from '../../components/facets'
import { useT } from '../../i18n'
import { CreatePlanDialog } from './components/plan-dialogs'
import { PlanSheet } from './components/plan-sheet'
import { useCanManagePlans, usePlanList } from './hooks/use-plans'

const keyOf = (row: PlanResponse) => row.id ?? ''

export function PlansPage({ state }: { state: UrlState }) {
  const t = useT('plans')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const spec = {
    sortable: ['code', 'id'],
    searchField: 'code',
    searchOp: 'starts_with' as const,
    searchPlaceholder: t('search'),
    facets: [useEnumFacet('status', 'status', ['ACTIVE', 'RETIRED'])],
  }
  const list = useListState(state, spec)
  const query = usePlanList(list)
  const rows = query.data?.data ?? []
  const record = useRecordSelection(state, rows, keyOf)
  const canManage = useCanManagePlans()
  const [creating, setCreating] = useState(false)

  const columns: DataTableColumn<PlanResponse>[] = [
    {
      key: 'code',
      header: t('code'),
      mobile: 'primary',
      cardText: (row) => row.code,
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-body-strong text-foreground">{row.name}</span>
          <span className="font-mono text-caption text-muted-foreground">{row.code}</span>
        </span>
      ),
    },
    {
      key: 'model',
      header: t('model'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => (row.model ? enumLabel(row.model) : ''),
    },
    {
      key: 'currency',
      header: tc('fields.currency'),
      sortable: false,
      mobile: 'secondary',
      render: (row) => row.currency,
    },
    {
      key: 'status',
      header: tc('fields.status'),
      mobile: 'badge',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'versions',
      header: t('versions'),
      sortable: false,
      mobile: 'meta',
      render: (row) => row.versions?.length ?? 0,
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
        <PlanSheet plan={record.selected} onClose={record.close} onPrev={record.prev} onNext={record.next} />
      )}
      <CreatePlanDialog open={creating} onClose={() => setCreating(false)} />
    </ResourcePage>
  )
}
