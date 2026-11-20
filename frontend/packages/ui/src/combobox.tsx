import * as Popover from '@radix-ui/react-popover'
import { Command } from 'cmdk'
import { type ReactNode, useId, useState } from 'react'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'

export interface ComboboxOption {
  value: string
  label: string
  /** A second line: an email, a code or the id itself. */
  hint?: string
}

export interface ComboboxProps {
  label: ReactNode
  hideLabel?: boolean
  hint?: ReactNode
  error?: ReactNode
  optional?: boolean
  value: string | null | undefined
  onChange: (value: string | null, option?: ComboboxOption) => void
  onBlur?: () => void
  options: ComboboxOption[]
  loading?: boolean
  /** Server-side search: the caller refetches `options` for the query. Without it the options filter locally. */
  onSearch?: (query: string) => void
  placeholder?: string
  /** What the trigger shows when the value is not among the current options, for example after a search. */
  selectedLabel?: ReactNode
  disabled?: boolean
  id?: string
  /** Offers the typed text itself as a value, for a field whose suggestions are not the whole set. */
  creatable?: boolean
  /** Clears the trigger after a pick, for a picker that adds to a list rather than holding one value. */
  resetOnSelect?: boolean
}

/**
 * Pick one record instead of pasting its id: a trigger that opens a searchable list. The value stays the id, so a
 * form still sends what the API expects.
 */
export function Combobox({
  label,
  hideLabel,
  hint,
  error,
  optional,
  value,
  onChange,
  onBlur,
  options,
  loading,
  onSearch,
  placeholder,
  selectedLabel,
  disabled,
  id,
  creatable,
  resetOnSelect,
}: ComboboxProps) {
  const t = useUiMessages()
  const generated = useId()
  const controlId = id ?? generated
  const noteId = hint || error ? `${controlId}-note` : undefined
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const selected = options.find((option) => option.value === value)
  const shown = resetOnSelect ? null : (selected?.label ?? selectedLabel ?? (value || null))
  const typed = query.trim()
  const offerTyped = creatable && typed !== '' && !options.some((option) => option.value === typed)
  const pick = (next: string | null, option?: ComboboxOption) => {
    onChange(next, option)
    setOpen(false)
    setQuery('')
  }

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={controlId} className={cn('text-body-strong text-foreground', hideLabel && 'sr-only')}>
        {label}
        {optional && <span className="ml-1 font-normal text-small text-muted-foreground">{t('field.optional')}</span>}
      </label>
      <Popover.Root
        open={open}
        onOpenChange={(next) => {
          setOpen(next)
          if (!next) onBlur?.()
        }}
      >
        <Popover.Trigger
          id={controlId}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={noteId}
          className={cn(
            'flex h-8 w-full items-center gap-2 rounded-md border bg-background px-2.5 text-left text-body shadow-xs',
            'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-halo',
            'disabled:cursor-not-allowed disabled:opacity-50',
            error ? 'border-destructive' : 'border-input',
          )}
        >
          <span className={cn('min-w-0 flex-1 truncate', !shown && 'text-muted-foreground')}>
            {shown ?? placeholder ?? t('combobox.placeholder')}
          </span>
          {selected?.hint && (
            <span className="truncate font-mono text-caption text-muted-foreground">{selected.hint}</span>
          )}
          <Icon name="chevrons-up-down" size={14} className="shrink-0 text-muted-foreground" />
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            align="start"
            sideOffset={4}
            className="z-[60] w-[var(--radix-popover-trigger-width)] min-w-64 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md"
          >
            <Command shouldFilter={!onSearch} label={typeof label === 'string' ? label : undefined}>
              <div className="flex items-center gap-1.5 border-b border-border px-1.5 pb-1.5">
                <Icon name="search" size={14} className="text-muted-foreground" />
                <Command.Input
                  value={query}
                  onValueChange={(next) => {
                    setQuery(next)
                    onSearch?.(next)
                  }}
                  placeholder={t('action.search')}
                  className="h-7 w-full bg-transparent text-small outline-none placeholder:text-muted-foreground"
                />
                {loading && <Icon name="loader" size={14} className="animate-spin text-muted-foreground" />}
              </div>
              <Command.List className="max-h-64 overflow-auto py-1">
                <Command.Empty className="px-2 py-3 text-center text-small text-muted-foreground">
                  {loading ? t('table.loading') : t('palette.empty')}
                </Command.Empty>
                {optional && value && (
                  <Command.Item
                    value="__clear__"
                    onSelect={() => pick(null)}
                    className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-small text-muted-foreground data-[selected=true]:bg-accent"
                  >
                    <Icon name="x" size={14} />
                    {t('action.clear')}
                  </Command.Item>
                )}
                {offerTyped && (
                  <Command.Item
                    value={`__typed__ ${typed}`}
                    onSelect={() => pick(typed)}
                    className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-small data-[selected=true]:bg-accent"
                  >
                    <Icon name="plus" size={14} className="shrink-0 text-muted-foreground" />
                    <span className="truncate">{t('combobox.use', { value: typed })}</span>
                  </Command.Item>
                )}
                {options.map((option) => (
                  <Command.Item
                    key={option.value}
                    value={`${option.label} ${option.hint ?? ''} ${option.value}`}
                    onSelect={() => pick(option.value, option)}
                    className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-small data-[selected=true]:bg-accent"
                  >
                    <Icon
                      name="check"
                      size={14}
                      className={cn('shrink-0', option.value === value ? 'opacity-100' : 'opacity-0')}
                    />
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate text-foreground">{option.label}</span>
                      {option.hint && (
                        <span className="truncate font-mono text-caption text-muted-foreground">{option.hint}</span>
                      )}
                    </span>
                  </Command.Item>
                ))}
              </Command.List>
            </Command>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      {(hint || error) && (
        <p id={noteId} className={cn('text-small', error ? 'text-destructive' : 'text-muted-foreground')}>
          {error ?? hint}
        </p>
      )}
    </div>
  )
}
