import { forwardRef, type InputHTMLAttributes, type ReactNode, useId } from 'react'
import { cn } from './cn'
import { Icon, type IconName } from './icon'

export interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size' | 'prefix'> {
  label: ReactNode
  hideLabel?: boolean
  hint?: ReactNode
  error?: ReactNode
  optional?: boolean
  type?: 'text' | 'email' | 'number' | 'password' | 'search' | 'url' | 'date' | 'datetime-local' | 'select' | 'textarea'
  options?: Array<string | { value: string; label: string }>
  prefix?: ReactNode
  suffix?: ReactNode
  icon?: IconName
  mono?: boolean
  size?: 'sm' | 'md'
}

/** Label, one control, one line of hint or error. */
export const Field = forwardRef<HTMLInputElement, FieldProps>(function Field(
  {
    label,
    hideLabel,
    hint,
    error,
    optional,
    type = 'text',
    options = [],
    prefix,
    suffix,
    icon,
    mono,
    size = 'md',
    className,
    id,
    ...props
  },
  ref,
) {
  const generated = useId()
  const controlId = id ?? generated
  const describedBy = hint || error ? `${controlId}-note` : undefined
  const shared = cn(
    'w-full rounded-md border bg-background text-body text-foreground shadow-xs placeholder:text-muted-foreground',
    'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-halo',
    'disabled:cursor-not-allowed disabled:opacity-50',
    size === 'sm' ? 'h-7 px-2' : 'h-8 px-2.5',
    mono && 'font-mono text-code',
    error ? 'border-destructive' : 'border-input',
    prefix && 'pl-7',
    suffix && 'pr-7',
    icon && 'pl-7',
    className,
  )

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={controlId} className={cn('text-body-strong text-foreground', hideLabel && 'sr-only')}>
        {label}
        {optional && <span className="ml-1 font-normal text-small text-muted-foreground">(optional)</span>}
      </label>
      <div className="relative">
        {(prefix || icon) && (
          <span className="pointer-events-none absolute inset-y-0 left-2 flex items-center text-muted-foreground">
            {prefix ?? (icon ? <Icon name={icon} size={14} /> : null)}
          </span>
        )}
        {type === 'select' ? (
          <select
            id={controlId}
            aria-describedby={describedBy}
            aria-invalid={error ? true : undefined}
            className={shared}
            {...(props as object)}
          >
            {options.map((option) => {
              const value = typeof option === 'string' ? option : option.value
              const text = typeof option === 'string' ? option : option.label
              return (
                <option key={value} value={value}>
                  {text}
                </option>
              )
            })}
          </select>
        ) : type === 'textarea' ? (
          <textarea
            id={controlId}
            aria-describedby={describedBy}
            aria-invalid={error ? true : undefined}
            className={cn(shared, 'h-24 py-1.5')}
            {...(props as object)}
          />
        ) : (
          <input
            ref={ref}
            id={controlId}
            type={type}
            aria-describedby={describedBy}
            aria-invalid={error ? true : undefined}
            className={shared}
            {...props}
          />
        )}
        {suffix && <span className="absolute inset-y-0 right-2 flex items-center text-muted-foreground">{suffix}</span>}
      </div>
      {(hint || error) && (
        <p id={describedBy} className={cn('text-small', error ? 'text-destructive' : 'text-muted-foreground')}>
          {error ?? hint}
        </p>
      )}
    </div>
  )
})
