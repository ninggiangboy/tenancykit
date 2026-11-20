import { Page } from '@suite/remote-kit'
import { Button, PermissionGate, Tabs, type UrlState } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../i18n'
import { AggregatesPanel } from './components/aggregates-panel'
import { RecordBatchDialog } from './components/record-batch-dialog'
import { RecordUsageDialog } from './components/record-usage-dialog'
import { UsageEvents } from './components/usage-events'
import { useCanRecordUsage } from './hooks'

type View = 'events' | 'aggregates'

/** One header for both views; the view and the open event live in the URL. */
export function UsagePage({ state }: { state: UrlState }) {
  const t = useT('usage')
  const view: View = state.get('view') === 'aggregates' ? 'aggregates' : 'events'
  const canRecord = useCanRecordUsage()
  const [dialog, setDialog] = useState<'one' | 'batch' | null>(null)

  return (
    <Page
      title={t('title')}
      description={t('description')}
      actions={
        <PermissionGate allowed={canRecord}>
          <Button variant="outline" icon="send" onClick={() => setDialog('batch')}>
            {t('batch')}
          </Button>
        </PermissionGate>
      }
      primaryAction={
        <PermissionGate allowed={canRecord}>
          <Button icon="plus" onClick={() => setDialog('one')}>
            {t('record')}
          </Button>
        </PermissionGate>
      }
    >
      <Tabs
        items={[
          { id: 'events', label: t('events') },
          { id: 'aggregates', label: t('aggregates') },
        ]}
        value={view}
        onChange={(id) => state.set({ view: id === 'events' ? null : id, open: null })}
        label={t('title')}
      />
      {view === 'events' ? <UsageEvents state={state} /> : <AggregatesPanel />}
      <RecordUsageDialog open={dialog === 'one'} onClose={() => setDialog(null)} />
      <RecordBatchDialog open={dialog === 'batch'} onClose={() => setDialog(null)} />
    </Page>
  )
}
