import type { LedgerEntryResponse, WalletResponse } from '@suite/api-client/billing'
import { ConfirmDialog, ListRow, QueryState, RowList, Section, Timestamp } from '@suite/remote-kit'
import { Button, Field, Money, PermissionGate } from '@suite/ui'
import { useEffect, useState } from 'react'
import { useT } from '../../../i18n'
import { useLedger, useReverseEntry } from '../hooks'

/** The append-only ledger, newest first; an entry is reversed by a new one, never edited. */
export function Ledger({ wallet, canReverse }: { wallet: WalletResponse; canReverse: boolean }) {
  const t = useT('wallets')
  const tc = useT('common')
  const query = useLedger(wallet.id)
  const [target, setTarget] = useState<LedgerEntryResponse | null>(null)
  const [reason, setReason] = useState('')
  useEffect(() => {
    if (!target) setReason('')
  }, [target])
  const reverseEntry = useReverseEntry(() => setTarget(null))

  return (
    <Section title={t('ledger')}>
      <QueryState
        query={query}
        skeletonHeight={60}
        isEmpty={(data) => (data.data ?? []).length === 0}
        empty={<p className="text-small text-muted-foreground">{t('emptyLedger')}</p>}
      >
        {(data) => (
          <RowList>
            {(data.data ?? []).map((entry) => (
              <ListRow
                key={entry.id}
                title={
                  <>
                    <span className="font-mono text-caption text-muted-foreground">#{entry.sequence}</span> {entry.type}
                  </>
                }
                meta={
                  <>
                    {entry.reference ?? tc('state.none')} · <Timestamp value={entry.createdAt} />
                  </>
                }
                trailing={<Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} signed />}
                action={
                  <PermissionGate allowed={canReverse && entry.type !== 'REVERSAL'}>
                    <Button variant="ghost" size="sm" onClick={() => setTarget(entry)}>
                      {t('reverse')}
                    </Button>
                  </PermissionGate>
                }
              />
            ))}
          </RowList>
        )}
      </QueryState>
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.reverseEntry')}
        description={t('reverseHint')}
        confirmLabel={t('reverse')}
        action={reverseEntry}
        confirmDisabled={reason.trim() === ''}
        onConfirm={() => target?.id && reverseEntry.mutate({ id: target.id, reason: reason.trim() })}
        impact={[
          { label: tc('fields.sequence'), value: `#${target?.sequence ?? ''}` },
          {
            label: tc('fields.amount'),
            value: <Money minor={target?.amount?.amount ?? 0} currency={target?.amount?.currency} />,
          },
        ]}
      >
        <Field label={tc('fields.reason')} value={reason} onChange={(event) => setReason(event.target.value)} />
      </ConfirmDialog>
    </Section>
  )
}
