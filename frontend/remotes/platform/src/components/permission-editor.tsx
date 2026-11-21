import { permissions } from '@suite/api-client'
import { Combobox, Icon } from '@suite/ui'
import { useMemo } from 'react'
import { useT } from '../i18n'

/** Every code the console itself calls; the backend knows more, so a person may still type one. */
const KNOWN_CODES = [
  ...new Set(Object.values(permissions).flatMap((group) => Object.values(group as Record<string, string>))),
].sort()

function groupByApp(codes: string[]): Array<[string, string[]]> {
  const groups = new Map<string, string[]>()
  for (const code of [...codes].sort()) {
    const app = code.split(':')[0] ?? code
    groups.set(app, [...(groups.get(app) ?? []), code])
  }
  return [...groups.entries()]
}

export interface PermissionEditorProps {
  value: string[]
  onChange?: (codes: string[]) => void
  /** Shows the codes without the add box or the remove buttons. */
  readOnly?: boolean
}

/** Permission codes as chips grouped by app, with a search box that suggests known codes and accepts any other. */
export function PermissionEditor({ value, onChange, readOnly }: PermissionEditorProps) {
  const tc = useT('common')
  const suggestions = useMemo(
    () => KNOWN_CODES.filter((code) => !value.includes(code)).map((code) => ({ value: code, label: code })),
    [value],
  )
  const groups = groupByApp(value)

  return (
    <div className="flex flex-col gap-3">
      {!readOnly && (
        <Combobox
          label={tc('permissions.add')}
          value={null}
          options={suggestions}
          creatable
          resetOnSelect
          placeholder="billing:invoice:read"
          onChange={(code) => {
            if (code && !value.includes(code)) onChange?.([...value, code])
          }}
        />
      )}
      {groups.length === 0 ? (
        <p className="text-small text-muted-foreground">{tc('permissions.none')}</p>
      ) : (
        <div className="flex flex-col gap-2 rounded-lg border border-border p-3">
          {groups.map(([app, codes]) => (
            <div key={app} className="flex flex-col gap-1.5">
              <span className="text-caption text-muted-foreground">{app}</span>
              <ul className="flex flex-wrap gap-1.5">
                {codes.map((code) => (
                  <li
                    key={code}
                    className="inline-flex items-center gap-1 rounded-md border border-border bg-muted pl-2 font-mono text-caption text-foreground"
                  >
                    {code}
                    {!readOnly && (
                      <button
                        type="button"
                        aria-label={tc('permissions.remove', { code })}
                        onClick={() => onChange?.(value.filter((entry) => entry !== code))}
                        className="motion-colors flex size-7 items-center justify-center rounded text-muted-foreground hover:text-foreground max-md:size-(--size-touch-target)"
                      >
                        <Icon name="x" size={12} />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
