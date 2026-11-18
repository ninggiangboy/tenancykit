import { type ApiClients, ApiError, createApiClients } from '@suite/api-client'
import type { ShellContext } from '@suite/shell-sdk'
import { Toaster, UiMessagesProvider, useToasts } from '@suite/ui'
import { QueryClient, QueryClientProvider, useMutation, useQueryClient } from '@tanstack/react-query'
import { createContext, type ReactNode, useCallback, useContext, useMemo } from 'react'
import { I18nextProvider } from 'react-i18next'
import { createRemoteI18n, type Translate } from '../i18n'

interface Runtime {
  ctx: ShellContext
  api: ApiClients
  t: Translate
}

const RuntimeContext = createContext<Runtime | null>(null)
const ToastContext = createContext<
  | ((toast: {
      title: ReactNode
      description?: ReactNode
      tone?: 'success' | 'danger' | 'info'
      action?: { label: string; onClick: () => void }
    }) => number)
  | null
>(null)

export interface RemoteProviderProps {
  ctx: ShellContext
  /** Tests answer with an absolute base; a browser uses the console's own origin. */
  resolveBaseUrl?: (api: 'platform' | 'billing') => string
  requestId?: () => string
  children: ReactNode
}

/** The whole seam a page needs: the shell's context, both API clients, the feature catalogs and toasts. */
export function RemoteProvider({ ctx, resolveBaseUrl, requestId, children }: RemoteProviderProps) {
  const i18n = useMemo(() => createRemoteI18n(ctx.locale), [ctx.locale])
  const api = useMemo(
    () => createApiClients({ getToken: ctx.getToken, resolveBaseUrl, requestId }),
    [ctx.getToken, resolveBaseUrl, requestId],
  )
  const queryClient = useMemo(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, staleTime: 5_000, refetchOnWindowFocus: false },
        },
      }),
    [],
  )
  const [toasts, pushToast, dismissToast] = useToasts()
  const t = useCallback<Translate>(
    (key, values) => String(values ? i18n.t(key, values as Record<string, string>) : i18n.t(key)),
    [i18n],
  )

  return (
    <I18nextProvider i18n={i18n}>
      <UiMessagesProvider locale={ctx.locale}>
        <QueryClientProvider client={queryClient}>
          <RuntimeContext.Provider value={{ ctx, api, t }}>
            <ToastContext.Provider value={pushToast}>
              {children}
              <Toaster toasts={toasts} onDismiss={dismissToast} />
            </ToastContext.Provider>
          </RuntimeContext.Provider>
        </QueryClientProvider>
      </UiMessagesProvider>
    </I18nextProvider>
  )
}

export function useRuntime(): Runtime {
  const runtime = useContext(RuntimeContext)
  if (!runtime) {
    throw new Error('a remote page renders inside RemoteProvider')
  }
  return runtime
}

export function useApi(): ApiClients {
  return useRuntime().api
}

export function useShellContext(): ShellContext {
  return useRuntime().ctx
}

export function useToast(): NonNullable<React.ContextType<typeof ToastContext>> {
  const push = useContext(ToastContext)
  if (!push) {
    throw new Error('a remote page renders inside RemoteProvider')
  }
  return push
}

/** The server's own sentence where it sent one; the status object hey-api throws is not an Error. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.detail
  }
  if (error && typeof error === 'object') {
    const problem = error as { detail?: unknown; title?: unknown; message?: unknown }
    if (typeof problem.detail === 'string') return problem.detail
    if (typeof problem.title === 'string' && problem.title) return problem.title
    if (typeof problem.message === 'string' && problem.message) return problem.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return 'The request failed.'
}

export function errorStatus(error: unknown): number | undefined {
  if (error instanceof ApiError) return error.status
  if (error && typeof error === 'object' && typeof (error as { status?: unknown }).status === 'number') {
    return (error as { status: number }).status
  }
  return undefined
}

/**
 * One write: it invalidates what is on screen, reports the server's failure, and lets the caller add its own toast
 * and close a dialog.
 */
export function useAction<TVars, TResult>(
  action: (vars: TVars) => Promise<TResult>,
  options: {
    onSuccess?: (result: TResult, vars: TVars) => void
    onError?: (error: unknown, vars: TVars) => void
  } = {},
) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: action,
    onSuccess: async (result, vars) => {
      await queryClient.invalidateQueries()
      options.onSuccess?.(result, vars)
    },
    onError: (error, vars) => {
      options.onError?.(error, vars)
      toast({ title: errorMessage(error), tone: 'danger' })
    },
  })
}
