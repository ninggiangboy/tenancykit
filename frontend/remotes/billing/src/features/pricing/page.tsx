import { Page } from '@suite/remote-kit'
import { Tabs, type UrlState } from '@suite/ui'
import { useT } from '../../i18n'
import { EstimatePanel } from './components/estimate-panel'
import { ReservePanel } from './components/reserve-panel'
import { UsageChargePanel } from './components/usage-charge-panel'

type Mode = 'estimate' | 'usage' | 'reservation'

export function PricingPage({ state }: { state: UrlState }) {
  const t = useT('pricing')
  const mode = (state.get('mode') as Mode | null) ?? 'estimate'
  return (
    <Page title={t('title')} description={t('description')}>
      <Tabs
        items={[
          { id: 'estimate', label: t('estimate') },
          { id: 'usage', label: t('usageCharge') },
          { id: 'reservation', label: t('reservation') },
        ]}
        value={mode}
        onChange={(id) => state.set({ mode: id === 'estimate' ? null : id })}
        label={t('title')}
      />
      {mode === 'estimate' && <EstimatePanel />}
      {mode === 'usage' && <UsageChargePanel />}
      {mode === 'reservation' && <ReservePanel />}
    </Page>
  )
}
