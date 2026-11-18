import type { ReactNode } from 'react'
import { cn } from './cn'

/**
 * One documented stacking order, so a remote's dialog always opens above the shell's sheet and palette. Every overlay
 * in the system uses one of these, never a number of its own.
 */
export const zIndex = {
  base: 0,
  sticky: 10,
  dropdown: 30,
  overlay: 40,
  modal: 50,
  toast: 60,
} as const

/** The five chart series, as CSS variables the Recharts wrapper paints with. */
export const chartColors = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
] as const

export function chartColor(index: number): string {
  return chartColors[Math.max(0, Math.min(index, chartColors.length - 1))] ?? chartColors[0]
}

/** A fixed-height frame for a chart; the chart itself is the caller's, drawn with the series above. */
export function ChartFrame({
  children,
  height = 240,
  className,
}: {
  children: ReactNode
  height?: number
  className?: string
}) {
  return (
    <div className={cn('w-full text-foreground', className)} style={{ height }} data-chart-frame>
      {children}
    </div>
  )
}
