import * as Popover from '@radix-ui/react-popover'
import { useState } from 'react'
import { Checkbox } from './checkbox'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'
import { PHONE_ROW, POPPER_MOTION } from './motion'

export interface FacetOption {
  value: string
  label: React.ReactNode
  count?: number
  icon?: React.ReactNode
}

export interface FacetedFilterProps {
  label: string
  options: FacetOption[]
  selected?: string[]
  defaultSelected?: string[]
  onChange?: (values: string[]) => void
  defaultOpen?: boolean
}

/** A dashed button that opens a searchable multi-select of one field's values, with counts. */
export function FacetedFilter({
  label,
  options,
  selected,
  defaultSelected = [],
  onChange,
  defaultOpen,
}: FacetedFilterProps) {
  const t = useUiMessages()
  const [internal, setInternal] = useState<string[]>(defaultSelected)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(defaultOpen ?? false)
  const values = selected ?? internal

  const commit = (next: string[]) => {
    if (selected === undefined) {
      setInternal(next)
    }
    onChange?.(next)
  }

  const visible = options.filter((option) =>
    String(typeof option.label === 'string' ? option.label : option.value)
      .toLowerCase()
      .includes(query.toLowerCase()),
  )

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        className={cn(
          'motion-colors inline-flex h-(--size-control-sm) items-center gap-1 rounded-md border border-dashed border-input px-2 text-small text-foreground hover:bg-accent max-md:h-(--size-touch-target) max-md:px-3',
          values.length > 0 && 'border-solid bg-selection',
        )}
      >
        <Icon name="plus-circle" size={14} className="text-muted-foreground" />
        {label}
        {values.length > 0 && <span className="rounded-full bg-secondary px-1.5 text-caption">{values.length}</span>}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={4}
          className={cn(
            'z-(--z-popper) w-64 rounded-lg border border-border bg-popover p-2 text-popover-foreground shadow-md',
            POPPER_MOTION,
          )}
        >
          <div className="mb-1 flex items-center gap-1 border-b border-border pb-1.5">
            <Icon name="search" size={14} className="text-muted-foreground" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t('action.search')}
              aria-label={t('action.search')}
              className="h-(--size-control-sm) w-full bg-transparent text-small outline-none placeholder:text-muted-foreground max-md:text-[16px]"
            />
          </div>
          <ul className="max-h-64 overflow-auto">
            {visible.length === 0 && (
              <li className="px-1 py-2 text-small text-muted-foreground">{t('palette.empty')}</li>
            )}
            {visible.map((option) => {
              const checked = values.includes(option.value)
              return (
                <li key={option.value}>
                  {/* biome-ignore lint/a11y/noLabelWithoutControl: the Radix checkbox inside is the control; the whole row is its label. */}
                  <label
                    className={cn(
                      'motion-colors flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1.5 text-small hover:bg-accent has-[:focus-visible]:bg-accent',
                      PHONE_ROW,
                    )}
                  >
                    <Checkbox
                      checked={checked}
                      onCheckedChange={() =>
                        commit(checked ? values.filter((value) => value !== option.value) : [...values, option.value])
                      }
                      aria-label={String(option.label)}
                    />
                    {option.icon}
                    <span className="flex-1">{option.label}</span>
                    {option.count !== undefined && (
                      <span className="text-caption text-muted-foreground">{option.count}</span>
                    )}
                  </label>
                </li>
              )
            })}
          </ul>
          {values.length > 0 && (
            <button
              type="button"
              onClick={() => commit([])}
              className={cn(
                'motion-colors mt-1 w-full rounded-md py-1 text-center text-small text-muted-foreground hover:bg-accent hover:text-foreground',
                PHONE_ROW,
              )}
            >
              {t('action.clear')}
            </button>
          )}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
