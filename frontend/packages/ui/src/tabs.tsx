import * as TabsPrimitive from '@radix-ui/react-tabs'
import { cn } from './cn'
import { Icon, type IconName } from './icon'

export interface TabItem {
  id: string
  label: React.ReactNode
  count?: number
  icon?: IconName
}

export interface TabsProps {
  items: TabItem[]
  value?: string
  defaultValue?: string
  onChange?: (id: string) => void
  /** Saved views of one list, or segmented views, or a line of sections of one record. */
  variant?: 'tiles' | 'segmented' | 'line'
  label?: string
  className?: string
}

/** Arrow keys move between tabs, as the design system's keyboard rule expects. */
export function Tabs({ items, value, defaultValue, onChange, variant = 'segmented', label, className }: TabsProps) {
  return (
    <TabsPrimitive.Root
      value={value}
      defaultValue={defaultValue ?? items[0]?.id}
      onValueChange={onChange}
      className={cn('flex flex-col gap-3', className)}
    >
      <TabsPrimitive.List
        aria-label={label}
        className={cn(
          'inline-flex items-center gap-1',
          variant === 'segmented' && 'self-start rounded-lg border border-border bg-muted p-0.5',
          variant === 'line' && 'border-b border-border',
          variant === 'tiles' &&
            'grid w-full gap-3 [grid-template-columns:repeat(auto-fit,minmax(140px,1fr))] max-md:-m-0.5 max-md:flex max-md:gap-2 max-md:overflow-x-auto max-md:p-0.5',
        )}
      >
        {items.map((item) => (
          <TabsPrimitive.Trigger
            key={item.id}
            value={item.id}
            // A tab here selects a view of the list below it, and there is no panel to point at; an aria-controls to
            // an id that does not exist is an invalid reference.
            aria-controls={undefined}
            className={cn(
              'motion-colors inline-flex h-(--size-control-sm) items-center gap-1.5 rounded-md px-2.5 text-body-strong text-muted-foreground',
              'hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              'data-[state=active]:text-foreground',
              variant === 'segmented' && 'data-[state=active]:bg-background data-[state=active]:shadow-sm',
              variant === 'line' &&
                '-mb-px rounded-none border-b-2 border-transparent px-2 data-[state=active]:border-foreground',
              variant === 'tiles' &&
                'h-auto flex-col items-start gap-0.5 rounded-lg bg-card px-3.5 py-3 text-small text-muted-foreground shadow-[0_0_0_1px_var(--border)] data-[state=active]:text-primary data-[state=active]:shadow-[0_0_0_2px_var(--primary)] max-md:h-(--size-touch-target) max-md:flex-row max-md:items-center max-md:gap-1.5 max-md:rounded-full max-md:px-3.5 max-md:data-[state=active]:bg-app-accent-soft max-md:data-[state=active]:shadow-none',
            )}
          >
            {item.icon && <Icon name={item.icon} size={14} />}
            {item.label}
            {item.count !== undefined && (
              <span
                className={cn(
                  'rounded-full bg-secondary px-1.5 text-caption text-muted-foreground',
                  variant === 'tiles' &&
                    'rounded-none bg-transparent p-0 text-figure text-foreground tabular-nums max-md:text-small max-md:font-semibold',
                )}
              >
                {variant === 'tiles' ? item.count.toLocaleString('en-US') : item.count}
              </span>
            )}
          </TabsPrimitive.Trigger>
        ))}
      </TabsPrimitive.List>
    </TabsPrimitive.Root>
  )
}
