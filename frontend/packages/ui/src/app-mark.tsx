import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { humanize } from './status-badge'

/** The apps of the suite, as the design system names them. */
export type AppCode =
  | 'platform'
  | 'billing'
  | 'mailroom'
  | 'notification'
  | 'survey'
  | 'attribution'
  | 'booking'
  | 'customer'
  | 'loyalty'
  | 'workflow'
  | 'helpdesk'

const glyphs: Record<AppCode, IconName> = {
  platform: 'shield',
  billing: 'receipt',
  mailroom: 'mail',
  notification: 'bell',
  survey: 'clipboard',
  attribution: 'pointer',
  booking: 'calendar',
  customer: 'contact',
  loyalty: 'gift',
  workflow: 'workflow',
  helpdesk: 'life-buoy',
}

const sizes = {
  sm: { tile: 'size-5', icon: 12 },
  md: { tile: 'size-6', icon: 14 },
  lg: { tile: 'size-8', icon: 18 },
} as const

export interface AppMarkProps {
  app: AppCode | string
  size?: keyof typeof sizes
  withName?: boolean
  name?: string
  className?: string
}

/** The neutral tile that tells one app from another: a glyph, never a colour code. */
export function AppMark({ app, size = 'md', withName, name, className }: AppMarkProps) {
  const glyph = glyphs[app as AppCode] ?? 'grid'
  const scale = sizes[size]
  return (
    <span className={cn('inline-flex items-center gap-2', className)}>
      <span
        aria-hidden
        className={cn(
          'inline-flex items-center justify-center rounded-md border border-border bg-muted text-muted-foreground',
          scale.tile,
        )}
      >
        <Icon name={glyph} size={scale.icon as 12 | 14 | 18} />
      </span>
      {withName && <span className="text-body-strong text-foreground">{name ?? humanize(app)}</span>}
    </span>
  )
}
