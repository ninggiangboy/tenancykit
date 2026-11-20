import type { PlanResponse, RuleDto } from '@suite/api-client/billing'
import { DetailRow, DetailRows, RecordSheet, Section, Timestamp } from '@suite/remote-kit'
import { Button, CopyId, PermissionGate, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useEnumLabel } from '../../../components/facets'
import { useT } from '../../../i18n'
import { useCanManagePlans } from '../hooks/use-plans'
import { PublishVersionDialog, RetirePlanDialog } from './plan-dialogs'

/** One line a person can read: what is charged, per what, every cycle. */
function RuleSummary({ rule, currency }: { rule?: RuleDto; currency?: string }) {
  const t = useT('plans')
  const parts: string[] = []
  if (rule?.fixedPrice) parts.push(`${t('fixedPrice')} ${rule.fixedPrice} ${currency ?? ''}`)
  const usage = rule?.components?.[0] ?? (rule?.metric ? rule : undefined)
  if (usage?.unitPrice) parts.push(`${usage.unitPrice} ${currency ?? ''} / ${usage.unit ?? usage.metric ?? ''}`)
  if (rule?.executionFee) parts.push(`${t('executionFee')} ${rule.executionFee}`)
  return <span>{parts.join(' + ') || '—'}</span>
}

export interface PlanSheetProps {
  plan: PlanResponse
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function PlanSheet({ plan, onClose, onPrev, onNext }: PlanSheetProps) {
  const t = useT('plans')
  const tc = useT('common')
  const enumLabel = useEnumLabel()
  const canManage = useCanManagePlans()
  const [dialog, setDialog] = useState<'publish' | 'retire' | null>(null)
  const retired = plan.status === 'RETIRED'
  const versions = [...(plan.versions ?? [])].sort((left, right) => (right.version ?? 0) - (left.version ?? 0))

  return (
    <>
      <RecordSheet
        open
        onClose={onClose}
        eyebrow={t('title')}
        title={plan.name ?? ''}
        description={<span className="font-mono">{plan.code}</span>}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={canManage && !retired}>
            <Button variant="outline" icon="plus" onClick={() => setDialog('publish')}>
              {t('publish')}
            </Button>
            <Button variant="danger" icon="ban" onClick={() => setDialog('retire')}>
              {t('retire')}
            </Button>
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={plan.status} />
            </DetailRow>
            <DetailRow label={t('model')}>{plan.model ? enumLabel(plan.model) : '—'}</DetailRow>
            <DetailRow label={tc('fields.currency')}>{plan.currency}</DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={plan.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={plan.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <Section title={t('versions')}>
            {versions.length === 0 ? (
              <p className="text-small text-muted-foreground">{tc('state.none')}</p>
            ) : (
              <ul className="divide-y divide-border rounded-lg border border-border">
                {versions.map((entry, index) => (
                  <li key={entry.version} className="flex flex-col gap-0.5 px-3 py-2 text-small">
                    <span className="flex items-center justify-between gap-2">
                      <span className="flex items-center gap-2 font-mono text-code text-foreground">
                        {t('version')} {entry.version}
                        {index === 0 && <StatusBadge status="ACTIVE" />}
                      </span>
                      <span className="text-caption text-muted-foreground">
                        <Timestamp value={entry.createdAt} />
                      </span>
                    </span>
                    <span className="text-muted-foreground">
                      {entry.billingCycle ? enumLabel(entry.billingCycle) : ''} ·{' '}
                      <RuleSummary rule={entry.rule} currency={plan.currency} />
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Section>
        </div>
      </RecordSheet>
      <PublishVersionDialog plan={plan} open={dialog === 'publish'} onClose={() => setDialog(null)} />
      <RetirePlanDialog plan={plan} open={dialog === 'retire'} onClose={() => setDialog(null)} />
    </>
  )
}
