import { type DefaultValues, type FieldValues, type UseFormReturn, useForm } from 'react-hook-form'
import type { z } from 'zod'
import { zodResolver } from './zod-resolver'

export type ZodForm<Schema extends z.ZodType<FieldValues, FieldValues>> = UseFormReturn<
  z.input<Schema>,
  unknown,
  z.output<Schema>
>

/** A form whose submit receives the schema's parsed output; errors show after the first submit, then on change. */
export function useZodForm<Schema extends z.ZodType<FieldValues, FieldValues>>(
  schema: Schema,
  defaultValues: DefaultValues<z.input<Schema>>,
): ZodForm<Schema> {
  return useForm<z.input<Schema>, unknown, z.output<Schema>>({
    resolver: zodResolver(schema),
    defaultValues,
    mode: 'onSubmit',
    reValidateMode: 'onChange',
  })
}
