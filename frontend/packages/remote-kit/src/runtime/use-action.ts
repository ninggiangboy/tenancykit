import { type QueryKey, useMutation, useQueryClient } from '@tanstack/react-query'
import { useKitT } from '../i18n/create-remote-i18n'
import { errorMessage } from './errors'
import { useToast } from './remote-provider'

export interface ActionOptions<TVars, TResult> {
  onSuccess?: (result: TResult, vars: TVars) => void
  onError?: (error: unknown, vars: TVars) => void
  /** A toast title on success; omitted means the caller reports success itself or the change speaks for itself. */
  successToast?: string | ((result: TResult, vars: TVars) => string)
  /** The failure toast's title where the server's sentence alone does not say what was being done. */
  errorTitle?: (error: unknown, vars: TVars) => string
  /** What to refetch after the write; every query by default, because a write can move any list on screen. */
  invalidate?: QueryKey[]
}

/** A failure stays long enough to read the server's sentence; a success is gone in the default six seconds. */
export const ERROR_TOAST_MS = 10_000

/**
 * One write: it refetches what is on screen, reports the server's failure as a toast, and lets the caller close a
 * dialog or toast its own success.
 */
export function useAction<TVars, TResult>(
  action: (vars: TVars) => Promise<TResult>,
  options: ActionOptions<TVars, TResult> = {},
) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const t = useKitT()
  return useMutation({
    mutationFn: action,
    onSuccess: async (result, vars) => {
      if (options.invalidate) {
        await Promise.all(options.invalidate.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      } else {
        await queryClient.invalidateQueries()
      }
      options.onSuccess?.(result, vars)
      const title =
        typeof options.successToast === 'function' ? options.successToast(result, vars) : options.successToast
      if (title) toast({ title, tone: 'success' })
    },
    onError: (error, vars) => {
      options.onError?.(error, vars)
      toast({
        title: options.errorTitle?.(error, vars) ?? errorMessage(error, t('state.requestFailed')),
        tone: 'danger',
        duration: ERROR_TOAST_MS,
      })
    },
  })
}

/** A fresh key per attempt; Billing deduplicates a retried write by it. */
export function idempotencyKey(): Record<'Idempotency-Key', string> {
  return { 'Idempotency-Key': globalThis.crypto.randomUUID() }
}
