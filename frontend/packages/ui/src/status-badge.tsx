import { cn } from './cn'
import { Icon, type IconName } from './icon'

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

/**
 * How a suite enum shows up. An unmapped value keeps its word, humanized, in a neutral pill, so a new server value is
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
  UNCOLLECTIBLE: 'danger',
  RETRYING: 'warning',
  WAITING: 'warning',
  COMPLETED: 'success',
  ENDED: 'neutral',
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

const pillClass: Record<Tone, string> = {
  neutral: 'bg-neutral-soft text-muted-foreground',
  info: 'bg-info-soft text-info',
  success: 'bg-success-soft text-success',
  warning: 'bg-warning-soft text-warning',
  danger: 'bg-destructive-soft text-destructive',
}

/** The icon says the tone without colour, so the pill still reads for a person who cannot tell the tints apart. */
const toneIcon: Partial<Record<Tone, IconName>> = {
  info: 'clock',
  success: 'check',
  warning: 'alert',
  danger: 'x-circle',
}

export interface StatusBadgeProps {
  /** A suite enum value such as `PAID`; its tone comes from the map. */
  status?: string
  tone?: Tone
  /** `plain` is muted text with no pill and no icon. */
  variant?: 'pill' | 'plain'
  children?: React.ReactNode
  className?: string
}

/** A tinted pill with an icon and a word. Never colour alone. */
export function StatusBadge({ status, tone, variant = 'pill', children, className }: StatusBadgeProps) {
  const resolved = tone ?? (status ? (tones[status] ?? 'neutral') : 'neutral')
  const word = children ?? (status ? humanize(status) : '')
  const icon = toneIcon[resolved]
  if (variant === 'plain') {
    return (
      <span className={cn('text-small text-muted-foreground', className)} data-tone={resolved}>
        {word}
      </span>
    )
  }
  return (
    <span
      className={cn(
        'inline-flex h-[22px] items-center gap-1 rounded-md px-2 text-caption font-semibold',
        pillClass[resolved],
        className,
      )}
      data-tone={resolved}
    >
      {icon && <Icon name={icon} size={12} strokeWidth={2.5} aria-hidden />}
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
