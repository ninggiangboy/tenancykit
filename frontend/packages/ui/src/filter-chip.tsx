import { cn } from './cn'
import { Icon } from './icon'

export interface FilterChipProps {
  field: string
  label?: string
  op?: 'eq' | 'neq' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'nin' | 'like' | 'is_null'
  value: string
  display?: React.ReactNode
  onRemove?: () => void
  className?: string
}

const opLabel: Record<NonNullable<FilterChipProps['op']>, string> = {
  eq: 'is',
  neq: 'is not',
  gt: 'more than',
  gte: 'at least',
  lt: 'less than',
  lte: 'at most',
  in: 'is any of',
  nin: 'is none of',
  like: 'matches',
  is_null: 'is empty',
}

/** One applied predicate of the list-query contract; its tooltip carries the wire syntax. */
export function FilterChip({ field, label, op = 'eq', value, display, onRemove, className }: FilterChipProps) {
  return (
    <span
      title={`filter[${field}][${op === 'like' ? 'contains' : op}]=${value}`}
      className={cn(
        'inline-flex h-(--size-control-sm) items-center gap-1 rounded-md border border-border bg-background pl-2 pr-1 text-small text-foreground max-md:h-(--size-touch-target)',
        className,
      )}
    >
      <span className="text-muted-foreground">{label ?? field}</span>
      <span className="text-muted-foreground">{opLabel[op]}</span>
      <span className="max-w-48 truncate font-medium">{display ?? value}</span>
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove the ${label ?? field} filter`}
          className="motion-colors ml-0.5 inline-flex size-6 items-center justify-center rounded-sm text-muted-foreground hover:bg-accent hover:text-foreground max-md:size-(--size-touch-target)"
        >
          <Icon name="x" size={12} />
        </button>
      )}
    </span>
  )
}
