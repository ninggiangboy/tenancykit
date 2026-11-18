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
  /** Segmented views of one list, or a line of sections of one record. */
  variant?: 'segmented' | 'line'
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
              'inline-flex h-7 items-center gap-1.5 rounded-md px-2.5 text-body-strong text-muted-foreground transition-colors duration-[120ms]',
              'hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              'data-[state=active]:text-foreground',
              variant === 'segmented' && 'data-[state=active]:bg-background data-[state=active]:shadow-sm',
              variant === 'line' &&
                '-mb-px rounded-none border-b-2 border-transparent px-2 data-[state=active]:border-foreground',
            )}
          >
            {item.icon && <Icon name={item.icon} size={14} />}
            {item.label}
            {item.count !== undefined && (
              <span className="rounded-full bg-secondary px-1.5 text-caption text-muted-foreground">{item.count}</span>
            )}
          </TabsPrimitive.Trigger>
        ))}
      </TabsPrimitive.List>
    </TabsPrimitive.Root>
  )
}
