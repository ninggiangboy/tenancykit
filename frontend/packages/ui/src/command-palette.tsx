import * as DialogPrimitive from '@radix-ui/react-dialog'
import { Command } from 'cmdk'
import { useEffect, useRef } from 'react'
import type { AppCode } from './app-mark'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { Kbd } from './kbd'
import { type KeyName, matchesHotkey } from './keys'
import { useUiMessages } from './messages'

export interface CommandItem {
  id?: string
  label: string
  hint?: string
  keywords?: string
  icon?: IconName
  app?: AppCode
  shortcut?: KeyName[]
  meta?: React.ReactNode
  onSelect?: () => void
}

export interface CommandPaletteProps {
  open: boolean
  onClose: () => void
  groups: Array<{ label: string; items: CommandItem[] }>
  placeholder?: string
  scope?: React.ReactNode
  defaultQuery?: string
  contained?: boolean
}

/** ⌘K: jump to an app, a section or a record, and run an action, from anywhere in the console. */
export function CommandPalette({
  open,
  onClose,
  groups,
  placeholder,
  scope,
  defaultQuery,
  contained,
}: CommandPaletteProps) {
  const t = useUiMessages()
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      const timer = setTimeout(() => inputRef.current?.focus(), 0)
      return () => clearTimeout(timer)
    }
  }, [open])

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogPrimitive.Portal container={contained ? document.body : undefined}>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-overlay" />
        <DialogPrimitive.Content
          className="fixed left-1/2 top-24 z-50 w-full max-w-xl -translate-x-1/2 overflow-hidden rounded-xl border border-border bg-popover p-0 text-popover-foreground shadow-xl"
          aria-label={placeholder ?? t('palette.placeholder')}
        >
          <DialogPrimitive.Title className="sr-only">{placeholder ?? t('palette.placeholder')}</DialogPrimitive.Title>
          <Command
            label={placeholder ?? t('palette.placeholder')}
            className="flex flex-col"
            defaultValue={defaultQuery}
          >
            <div className="flex items-center gap-2 border-b border-border px-3">
              <Icon name="search" size={16} className="text-muted-foreground" />
              <Command.Input
                ref={inputRef}
                placeholder={placeholder ?? t('palette.placeholder')}
                className="h-10 w-full bg-transparent text-body outline-none placeholder:text-muted-foreground"
              />
              {scope}
              <Kbd keys={['esc']} />
            </div>
            <Command.List className="max-h-80 overflow-auto p-1.5">
              <Command.Empty className="px-2 py-6 text-center text-small text-muted-foreground">
                {t('palette.empty')}
              </Command.Empty>
              {groups.map((group) => (
                <Command.Group
                  key={group.label}
                  heading={group.label}
                  className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-caption [&_[cmdk-group-heading]]:text-muted-foreground"
                >
                  {group.items.map((item) => (
                    <Command.Item
                      key={item.id ?? item.label}
                      value={[item.label, item.keywords, item.hint].filter(Boolean).join(' ')}
                      onSelect={() => {
                        item.onSelect?.()
                        onClose()
                      }}
                      className={cn(
                        'flex cursor-pointer select-none items-center gap-2 rounded-md px-2 py-1.5 text-small outline-none',
                        'data-[selected=true]:bg-accent data-[selected=true]:text-accent-foreground',
                      )}
                    >
                      {item.icon && <Icon name={item.icon} size={14} className="text-muted-foreground" />}
                      <span className="flex-1 truncate">{item.label}</span>
                      {item.hint && <span className="truncate text-caption text-muted-foreground">{item.hint}</span>}
                      {item.meta}
                      {item.shortcut && <Kbd keys={item.shortcut} />}
                    </Command.Item>
                  ))}
                </Command.Group>
              ))}
            </Command.List>
          </Command>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

const FIELD_SELECTOR = 'input, textarea, select, [contenteditable="true"]'

/** Binds a shortcut such as `mod+k` or `/`; keys typed in a field are left alone unless `allowInFields`. */
export function useHotkey(combo: string, handler: (event: KeyboardEvent) => void, allowInFields = false): void {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!allowInFields && (event.target as HTMLElement | null)?.closest?.(FIELD_SELECTOR)) {
        return
      }
      if (matchesHotkey(combo, event)) {
        event.preventDefault()
        handler(event)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [combo, handler, allowInFields])
}
