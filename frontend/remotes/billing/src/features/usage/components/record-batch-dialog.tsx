import type { BatchResponse, UsageEventRequest } from '@suite/api-client/billing'
import { Section } from '@suite/remote-kit'
import { Alert, Button, Dialog, Field, StatusBadge } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useRecordBatch } from '../hooks'

/** Checks the pasted JSON before sending, so a typo is reported by event number instead of by the server. */
function parseEvents(text: string, t: ReturnType<typeof useT>): { events?: UsageEventRequest[]; error?: string } {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (error) {
    return { error: (error as Error).message }
  }
  if (!Array.isArray(parsed)) return { error: t('notArray') }
  const bad = parsed.findIndex(
    (event) =>
      typeof event !== 'object' ||
      event === null ||
      typeof (event as UsageEventRequest).customerId !== 'string' ||
      typeof (event as UsageEventRequest).metric !== 'string' ||
      (event as UsageEventRequest).quantity === undefined,
  )
  if (bad >= 0) return { error: t('batchInvalid', { index: bad + 1 }) }
  return { events: (parsed as UsageEventRequest[]).map((event) => ({ ...event, quantity: String(event.quantity) })) }
}

export function RecordBatchDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT('usage')
  const tc = useT('common')
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const [result, setResult] = useState<BatchResponse | null>(null)
  const batch = useRecordBatch()
  const close = () => {
    onClose()
    setText('')
    setError('')
    setResult(null)
  }
  const submit = () => {
    const parsed = parseEvents(text, t)
    setError(parsed.error ?? '')
    if (parsed.events) batch.mutate(parsed.events, { onSuccess: setResult })
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t('batchTitle')}
      description={t('batchHint')}
      size="lg"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={close}>
            {tc('actions.close')}
          </Button>
          <Button size="sm" loading={batch.isPending} disabled={text.trim() === ''} onClick={submit}>
            {t('batch')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field
          label={t('json')}
          type="textarea"
          mono
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder='[{ "customerId": "…", "metric": "api_calls", "quantity": "120" }]'
          className="h-40"
        />
        {error && <Alert tone="danger">{error}</Alert>}
        {result && (
          <Section title={t('results')}>
            <ul className="max-h-56 divide-y divide-border overflow-auto rounded-lg border border-border">
              {(result.results ?? []).map((entry, index) => (
                <li
                  key={entry.event?.id ?? index}
                  className="flex items-center justify-between gap-2 px-3 py-2 text-small"
                >
                  <span className="truncate font-mono text-code">
                    {entry.event?.metric} × {entry.event?.quantity}
                  </span>
                  <StatusBadge tone={entry.replayed ? 'neutral' : 'success'}>
                    {entry.replayed ? t('replayed') : t('accepted')}
                  </StatusBadge>
                </li>
              ))}
            </ul>
          </Section>
        )}
      </div>
    </Dialog>
  )
}
