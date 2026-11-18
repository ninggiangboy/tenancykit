import { cn } from './cn'

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

/**
 * How a suite enum shows up. An unmapped value keeps its word, humanized, with a neutral dot, so a new server value is
 * never invisible.
 */
const tones: Record<string, Tone> = {
  // money
  PAID: 'success',
  SUCCEEDED: 'success',
  OPEN: 'info',
  DRAFT: 'neutral',
  VOID: 'neutral',
  VOIDED: 'neutral',
  PAST_DUE: 'warning',
  PENDING: 'warning',
  PROCESSING: 'warning',
  REQUIRING_ACTION: 'warning',
  FAILED: 'danger',
  REFUNDED: 'neutral',
  CANCELED: 'neutral',
  EXPIRED: 'danger',
  // access and platform
  ACTIVE: 'success',
  ENABLED: 'success',
  DEACTIVATED: 'neutral',
  DISABLED: 'neutral',
  REVOKED: 'danger',
  INVITED: 'warning',
  SUSPENDED: 'danger',
  // deliveries and jobs
  DELIVERED: 'success',
  DEAD: 'danger',
  SKIPPED: 'neutral',
  RUNNING: 'info',
  TRIALING: 'info',
  // generic
  YES: 'success',
  NO: 'neutral',
}

const toneClass: Record<Tone, string> = {
  neutral: 'bg-muted-foreground',
  info: 'bg-info',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-destructive',
}

export interface StatusBadgeProps {
  /** A suite enum value such as `PAID`; its tone comes from the map. */
  status?: string
  tone?: Tone
  variant?: 'outline' | 'plain'
  children?: React.ReactNode
  className?: string
}

/** A coloured dot and a word, in an outlined pill. Never a block of colour. */
export function StatusBadge({ status, tone, variant = 'outline', children, className }: StatusBadgeProps) {
  const resolved = tone ?? (status ? (tones[status] ?? 'neutral') : 'neutral')
  const word = children ?? (status ? humanize(status) : '')
  return (
    <span
      className={cn(
        'inline-flex h-5 items-center gap-1.5 rounded-full px-2 text-caption text-foreground',
        variant === 'outline' ? 'border border-border bg-background' : 'bg-muted',
        className,
      )}
      data-tone={resolved}
    >
      <span aria-hidden className={cn('size-1.5 rounded-full', toneClass[resolved])} />
      {word}
    </span>
  )
}

/** `PAST_DUE` becomes "Past due"; a word that is already prose is left alone. */
export function humanize(value: string): string {
  if (!/^[A-Z0-9_ ]+$/.test(value)) {
    return value
  }
  const words = value.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}
