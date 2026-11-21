import { cn } from './cn'

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
      className={cn(
        'block animate-shimmer rounded-sm bg-[linear-gradient(90deg,var(--muted)_0%,var(--accent)_50%,var(--muted)_100%)] bg-[length:200%_100%]',
        circle && 'rounded-full',
        className,
      )}
      style={{ width: width ?? '100%', height }}
    />
  )
}
