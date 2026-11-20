import type { LedgerEntryResponse, WalletResponse } from '@suite/api-client/billing'
import { ConfirmDialog, Section, Timestamp } from '@suite/remote-kit'
import { Button, Field, Money, PermissionGate, Skeleton } from '@suite/ui'
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
  const entries = query.data?.data ?? []

  return (
    <Section title={t('ledger')}>
      {query.isLoading ? (
        <Skeleton width="100%" height={60} />
      ) : entries.length === 0 ? (
        <p className="text-small text-muted-foreground">{t('emptyLedger')}</p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {entries.map((entry) => (
            <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
              <span className="flex min-w-0 flex-col">
                <span className="flex items-center gap-2">
                  <span className="font-mono text-caption text-muted-foreground">#{entry.sequence}</span>
                  <span className="text-foreground">{entry.type}</span>
                </span>
                <span className="truncate text-caption text-muted-foreground">
                  {entry.reference ?? tc('state.none')} · <Timestamp value={entry.createdAt} />
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-1">
                <Money minor={entry.amount?.amount ?? 0} currency={entry.amount?.currency} signed />
                <PermissionGate allowed={canReverse && entry.type !== 'REVERSAL'}>
                  <Button variant="ghost" size="sm" onClick={() => setTarget(entry)}>
                    {t('reverse')}
                  </Button>
                </PermissionGate>
              </span>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={tc('confirm.reverseEntry')}
        description={t('reverseHint')}
        confirmLabel={t('reverse')}
        busy={reverseEntry.isPending}
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
