import * as DropdownPrimitive from '@radix-ui/react-dropdown-menu'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { Kbd } from './kbd'
import type { KeyName } from './keys'

export interface MenuItem {
  label?: React.ReactNode
  icon?: IconName
  shortcut?: KeyName[]
  onSelect?: () => void
  danger?: boolean
  disabled?: boolean
  separator?: boolean
  heading?: React.ReactNode
}

export interface DropdownMenuProps {
  items: MenuItem[]
  trigger?: React.ReactElement
  label?: string
  align?: 'start' | 'end'
  defaultOpen?: boolean
}

/** Row actions and page menus. The trigger is usually an icon-only button with a Tooltip. */
export function DropdownMenu({ items, trigger, label, align = 'end', defaultOpen }: DropdownMenuProps) {
  return (
    <DropdownPrimitive.Root defaultOpen={defaultOpen}>
      <DropdownPrimitive.Trigger asChild>
        {trigger ?? (
          <button
            type="button"
            aria-label={label ?? 'Open the menu'}
            className="inline-flex size-8 items-center justify-center rounded-md hover:bg-accent"
          >
            <Icon name="more" size={16} />
          </button>
        )}
      </DropdownPrimitive.Trigger>
      <DropdownPrimitive.Portal>
        <DropdownPrimitive.Content
          align={align}
          sideOffset={4}
          className="z-30 min-w-44 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md"
        >
          {items.map((item) => {
            const key = `${item.heading ?? ''}${item.label ?? ''}${item.separator ? 'separator' : ''}`
            return item.separator ? (
              <DropdownPrimitive.Separator key={key} className="my-1 h-px bg-border" />
            ) : item.heading ? (
              <DropdownPrimitive.Label key={key} className="px-2 py-1 text-caption text-muted-foreground">
                {item.heading}
              </DropdownPrimitive.Label>
            ) : (
              <DropdownPrimitive.Item
                key={key}
                disabled={item.disabled}
                onSelect={item.onSelect}
                className={cn(
                  'flex cursor-pointer select-none items-center gap-1.5 rounded-md px-2 py-1.5 text-small outline-none',
                  'data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground',
                  'data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
                  item.danger && 'text-destructive data-[highlighted]:text-destructive',
                )}
              >
                {item.icon && <Icon name={item.icon} size={14} className="text-muted-foreground" />}
                <span className="flex-1">{item.label}</span>
                {item.shortcut && <Kbd keys={item.shortcut} />}
              </DropdownPrimitive.Item>
            )
          })}
        </DropdownPrimitive.Content>
      </DropdownPrimitive.Portal>
    </DropdownPrimitive.Root>
  )
}
