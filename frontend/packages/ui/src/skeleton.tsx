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
      className={cn('block animate-pulse rounded-sm bg-muted', circle && 'rounded-full', className)}
      style={{ width: width ?? '100%', height }}
    />
  )
}
