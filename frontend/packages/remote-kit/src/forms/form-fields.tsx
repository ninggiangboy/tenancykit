import { Combobox, type ComboboxOption, Field, type FieldProps, Switch } from '@suite/ui'
import { type ReactNode, useEffect, useState } from 'react'
import { type Control, type FieldPath, type FieldValues, useController } from 'react-hook-form'
import { useKitT } from '../i18n/create-remote-i18n'
import { type KitKey, kitEn } from '../i18n/kit-catalog'

interface Bound<Values extends FieldValues> {
  control: Control<Values, unknown, FieldValues>
  name: FieldPath<Values>
}

/** A schema message is a kit key; anything else is already a sentence. */
function useErrorText() {
  const t = useKitT()
  return (message: string | undefined) =>
    message === undefined ? undefined : message in kitEn ? t(message as KitKey) : message
}

export type FormFieldProps<Values extends FieldValues> = Bound<Values> &
  Omit<FieldProps, 'name' | 'value' | 'defaultValue' | 'onChange' | 'onBlur' | 'error'>

/** A `Field` bound to one form value; it shows the value's error under the control. */
export function FormField<Values extends FieldValues>({ control, name, ...props }: FormFieldProps<Values>) {
  const errorText = useErrorText()
  const { field, fieldState } = useController({ control, name })
  return (
    <Field
      {...props}
      ref={field.ref}
      name={field.name}
      value={field.value ?? ''}
      onChange={(event) => field.onChange((event.target as HTMLInputElement).value)}
      onBlur={field.onBlur}
      error={errorText(fieldState.error?.message)}
    />
  )
}

export interface FormSwitchProps<Values extends FieldValues> extends Bound<Values> {
  label: ReactNode
  hint?: ReactNode
}

export function FormSwitch<Values extends FieldValues>({ control, name, label, hint }: FormSwitchProps<Values>) {
  const { field } = useController({ control, name })
  return (
    <div className="flex items-start justify-between gap-3">
      <div className="flex flex-col gap-0.5">
        <span className="text-body-strong text-foreground">{label}</span>
        {hint && <span className="text-small text-muted-foreground">{hint}</span>}
      </div>
      <Switch checked={Boolean(field.value)} onCheckedChange={field.onChange} aria-label={String(label)} />
    </div>
  )
}

export interface EntityOptions {
  options: ComboboxOption[]
  loading: boolean
}

export interface FormEntityPickerProps<Values extends FieldValues> extends Bound<Values> {
  label: ReactNode
  hint?: ReactNode
  optional?: boolean
  placeholder?: string
  /** Fetches the options for a search; it is a hook, so it runs on every render of the picker. */
  useOptions: (query: string) => EntityOptions
}

/** Pick one record by searching for it; the form value is its id. */
export function FormEntityPicker<Values extends FieldValues>({
  control,
  name,
  useOptions,
  ...props
}: FormEntityPickerProps<Values>) {
  const errorText = useErrorText()
  const { field, fieldState } = useController({ control, name })
  const [query, setQuery] = useState('')
  const search = useDebounced(query, 250)
  const { options, loading } = useOptions(search)
  return (
    <Combobox
      {...props}
      value={field.value ?? ''}
      onChange={(value) => field.onChange(value ?? '')}
      onBlur={field.onBlur}
      options={options}
      loading={loading}
      onSearch={setQuery}
      error={errorText(fieldState.error?.message)}
    />
  )
}

function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return debounced
}
