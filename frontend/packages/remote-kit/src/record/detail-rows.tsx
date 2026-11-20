import { CopyId } from '@suite/ui'
import type { ReactNode } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'

export function DetailRows({ children }: { children: ReactNode }) {
  return <dl className="divide-y divide-border rounded-lg border border-border">{children}</dl>
}

/** One label and value; a long value wraps under the label instead of squeezing it. */
export function DetailRow({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[minmax(7rem,40%)_1fr] items-start gap-3 px-3 py-2 text-small">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words text-right text-body-strong text-foreground">{children}</dd>
    </div>
  )
}

/** A value that may be absent; absent shows the empty mark. */
export function Maybe({ value, children }: { value: unknown; children?: ReactNode }) {
  const t = useKitT()
  if (value === null || value === undefined || value === '') {
    return <span className="text-muted-foreground">{t('state.none')}</span>
  }
  return <>{children ?? String(value)}</>
}

export function IdValue({ value }: { value?: string | null }) {
  return (
    <Maybe value={value}>
      <CopyId value={value ?? ''} />
    </Maybe>
  )
}

/** A UTC timestamp as `YYYY-MM-DD HH:MM`, the console's one date format; the column headers say UTC. */
export function Timestamp({ value }: { value?: string | null }) {
  if (!value) return <Maybe value={value} />
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return <span>{value}</span>
  return (
    <time dateTime={value} className="whitespace-nowrap tabular-nums">
      {date.toISOString().replace('T', ' ').slice(0, 16)}
    </time>
  )
}
