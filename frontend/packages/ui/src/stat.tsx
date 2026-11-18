import { cn } from './cn'
import { Icon } from './icon'

export interface StatProps {
  label: React.ReactNode
  value: React.ReactNode
  unit?: string
  /** Optional sparkline values, drawn as a hairline; data never animates. */
  spark?: number[]
  delta?: string
  deltaTone?: 'success' | 'danger' | 'neutral'
  caption?: React.ReactNode
  className?: string
}

const deltaClass = {
  success: 'text-success',
  danger: 'text-destructive',
  neutral: 'text-muted-foreground',
} as const

/** One measure: the number is the point, the label is quiet, an optional sparkline and delta give the trend. */
export function Stat({ label, value, unit, spark, delta, deltaTone = 'neutral', caption, className }: StatProps) {
  return (
    <div className={cn('flex flex-col gap-1 rounded-lg border border-border bg-card p-5', className)}>
      <span className="text-caption text-muted-foreground">{label}</span>
      <div className="flex items-baseline gap-1">
        <span className="text-metric text-foreground">{value}</span>
        {unit && <span className="text-small text-muted-foreground">{unit}</span>}
      </div>
      {spark && spark.length > 1 && <Spark values={spark} />}
      {(delta || caption) && (
        <div className="flex items-center gap-2 text-small">
          {delta && (
            <span className={cn('inline-flex items-center gap-0.5', deltaClass[deltaTone])}>
              <Icon name={deltaTone === 'danger' ? 'arrow-down-right' : 'arrow-up-right'} size={12} />
              {delta}
            </span>
          )}
          {caption && <span className="text-muted-foreground">{caption}</span>}
        </div>
      )}
    </div>
  )
}

function Spark({ values }: { values: number[] }) {
  const max = Math.max(...values)
  const min = Math.min(...values)
  const span = max - min || 1
  const points = values
    .map((value, index) => {
      const x = (index / (values.length - 1)) * 100
      const y = 20 - ((value - min) / span) * 18
      return `${x.toFixed(2)},${y.toFixed(2)}`
    })
    .join(' ')
  return (
    <svg viewBox="0 0 100 20" preserveAspectRatio="none" aria-hidden className="mt-1 h-5 w-full text-chart-1">
      <polyline fill="none" stroke="currentColor" strokeWidth="1.5" vectorEffect="non-scaling-stroke" points={points} />
    </svg>
  )
}
