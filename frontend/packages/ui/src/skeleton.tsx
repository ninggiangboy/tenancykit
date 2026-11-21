import { cn } from './cn'

/**
 * A placeholder block. The highlight is a child that slides with `transform`, which the compositor does on its own;
 * animating the block's background would repaint every placeholder on the main thread, and a table shows 25 at once.
 */
export function Skeleton({
  width,
  height = 14,
  circle,
  className,
}: {
  width?: string | number
  height?: number
  circle?: boolean
  className?: string
}) {
  return (
    <span
      aria-hidden
      className={cn('relative block overflow-hidden rounded-sm bg-muted', circle && 'rounded-full', className)}
      style={{ width: width ?? '100%', height }}
    >
      <span className="absolute inset-0 animate-shimmer bg-[linear-gradient(90deg,transparent_0%,var(--accent)_50%,transparent_100%)] motion-reduce:hidden" />
    </span>
  )
}
