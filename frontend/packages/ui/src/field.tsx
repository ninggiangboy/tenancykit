import { forwardRef, type InputHTMLAttributes, type ReactNode, type Ref, useId } from 'react'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { useUiMessages } from './messages'

const utcInputProps = {
  inputMode: 'numeric',
  placeholder: 'YYYY-MM-DD HH:mm',
  pattern: '\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}',
  autoComplete: 'off',
} as const

export interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size' | 'prefix'> {
  label: ReactNode
  hideLabel?: boolean
  hint?: ReactNode
  error?: ReactNode
  optional?: boolean
  type?: 'text' | 'email' | 'number' | 'password' | 'search' | 'url' | 'date' | 'datetime-utc' | 'select' | 'textarea'
  options?: Array<string | { value: string; label: string }>
  prefix?: ReactNode
  suffix?: ReactNode
  icon?: IconName
  mono?: boolean
  size?: 'sm' | 'md'
}

/**
 * Label, one control, one line of hint or error. The ref reaches whichever control renders, so a form library can
 * register a select or a textarea the same way as an input.
 */
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
  const t = useUiMessages()
  const generated = useId()
  const controlId = id ?? generated
  const describedBy = hint || error ? `${controlId}-note` : undefined
  const shared = cn(
    'w-full rounded-md border bg-background text-body text-foreground shadow-xs placeholder:text-muted-foreground',
    'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-halo',
    'disabled:cursor-not-allowed disabled:opacity-50',
    size === 'sm' ? 'h-(--size-control-sm) px-2.5' : 'h-(--size-control-md) px-3',
    'max-md:text-base',
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
        {optional && <span className="ml-1 font-normal text-small text-muted-foreground">{t('field.optional')}</span>}
      </label>
      <div className="relative">
        {(prefix || icon) && (
          <span className="pointer-events-none absolute inset-y-0 left-2 flex items-center text-muted-foreground">
            {prefix ?? (icon ? <Icon name={icon} size={14} /> : null)}
          </span>
        )}
        {type === 'select' ? (
          <select
            ref={ref as unknown as Ref<HTMLSelectElement>}
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
            ref={ref as unknown as Ref<HTMLTextAreaElement>}
            id={controlId}
            aria-describedby={describedBy}
            aria-invalid={error ? true : undefined}
            className={cn(shared, 'h-24 py-1.5', className)}
            {...(props as object)}
          />
        ) : (
          <input
            ref={ref}
            id={controlId}
            type={type === 'datetime-utc' ? 'text' : type}
            aria-describedby={describedBy}
            aria-invalid={error ? true : undefined}
            className={shared}
            {...(type === 'datetime-utc' ? utcInputProps : {})}
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
