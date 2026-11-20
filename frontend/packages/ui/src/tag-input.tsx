import { type ReactNode, useMemo } from 'react'
import { Combobox } from './combobox'
import { Icon } from './icon'
import { useUiMessages } from './messages'

export interface TagInputProps {
  label: ReactNode
  hint?: ReactNode
  error?: ReactNode
  value: string[]
  onChange: (value: string[]) => void
  /** Values to suggest; anything else can still be typed. */
  suggestions?: string[]
  placeholder?: string
}

/** A list of short values as removable chips, added through a search box that suggests and accepts any text. */
export function TagInput({ label, hint, error, value, onChange, suggestions = [], placeholder }: TagInputProps) {
  const t = useUiMessages()
  const options = useMemo(
    () => suggestions.filter((entry) => !value.includes(entry)).map((entry) => ({ value: entry, label: entry })),
    [suggestions, value],
  )
  return (
    <div className="flex flex-col gap-2">
      <Combobox
        label={label}
        hint={hint}
        error={error}
        value={null}
        options={options}
        creatable
        resetOnSelect
        placeholder={placeholder}
        onChange={(entry) => {
          if (entry && !value.includes(entry)) onChange([...value, entry])
        }}
      />
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1.5">
          {value.map((entry) => (
            <li
              key={entry}
              className="inline-flex items-center gap-1 rounded-md border border-border bg-muted px-1.5 py-0.5 font-mono text-caption text-foreground"
            >
              {entry}
              <button
                type="button"
                aria-label={`${t('action.remove')} ${entry}`}
                onClick={() => onChange(value.filter((item) => item !== entry))}
                className="rounded text-muted-foreground hover:text-foreground"
              >
                <Icon name="x" size={12} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
