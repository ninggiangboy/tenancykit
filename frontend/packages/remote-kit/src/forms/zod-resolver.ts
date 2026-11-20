import type { FieldError, FieldErrors, FieldValues, Resolver } from 'react-hook-form'
import type { z } from 'zod'

/**
 * React Hook Form's resolver for a Zod 4 schema: the parsed output is what `handleSubmit` receives, and each issue
 * lands on its field. Written here instead of pulling in a resolver package for twenty lines.
 */
export function zodResolver<Schema extends z.ZodType<FieldValues, FieldValues>>(
  schema: Schema,
): Resolver<z.input<Schema>, unknown, z.output<Schema>> {
  return async (values) => {
    const result = await schema.safeParseAsync(values)
    if (result.success) {
      return { values: result.data, errors: {} }
    }
    const errors: Record<string, unknown> = {}
    for (const issue of result.error.issues) {
      const path = issue.path.map(String)
      if (path.length === 0) path.push('root')
      let node = errors
      for (const [index, segment] of path.entries()) {
        if (index === path.length - 1) {
          node[segment] ??= { type: issue.code, message: issue.message } satisfies FieldError
        } else {
          node[segment] ??= {}
          node = node[segment] as Record<string, unknown>
        }
      }
    }
    return { values: {}, errors: errors as FieldErrors<z.input<Schema>> }
  }
}
