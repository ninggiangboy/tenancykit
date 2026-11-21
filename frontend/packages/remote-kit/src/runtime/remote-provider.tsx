import { type ApiClients, createApiClients } from '@suite/api-client'
import type { Locale, ShellContext } from '@suite/shell-sdk'
import { Toaster, UiMessagesProvider, useToasts } from '@suite/ui'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { i18n } from 'i18next'
import { createContext, type ReactNode, useContext, useMemo } from 'react'
import { I18nextProvider } from 'react-i18next'

export interface ToastInput {
  title: ReactNode
  description?: ReactNode
  tone?: 'success' | 'danger' | 'info'
  /** How long it stays, in milliseconds; the default is six seconds. */
  duration?: number
  action?: { label: string; onClick: () => void }
}

export type PushToast = (toast: ToastInput) => number

interface Runtime {
  ctx: ShellContext
  api: ApiClients
}

const RuntimeContext = createContext<Runtime | null>(null)
const ToastContext = createContext<PushToast | null>(null)

export interface RemoteProviderProps {
  ctx: ShellContext
  /** The remote's catalogs for a locale; `createRemoteI18n` from this kit builds it. */
  createI18n: (locale: Locale) => i18n
  /** Tests answer with an absolute base; a browser uses the console's own origin. */
  resolveBaseUrl?: (api: 'platform' | 'billing') => string
  requestId?: () => string
  children: ReactNode
}

/** The whole seam a page needs: the shell's context, both API clients, the catalogs, the query cache and toasts. */
export function RemoteProvider({ ctx, createI18n, resolveBaseUrl, requestId, children }: RemoteProviderProps) {
  const i18n = useMemo(() => createI18n(ctx.locale), [createI18n, ctx.locale])
  const api = useMemo(
    () => createApiClients({ getToken: ctx.getToken, resolveBaseUrl, requestId }),
    [ctx.getToken, resolveBaseUrl, requestId],
  )
  const queryClient = useMemo(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: false, staleTime: 5_000, refetchOnWindowFocus: false } },
      }),
    [],
  )
  const [toasts, pushToast, dismissToast] = useToasts()
  const runtime = useMemo(() => ({ ctx, api }), [ctx, api])

  return (
    <I18nextProvider i18n={i18n}>
      <UiMessagesProvider locale={ctx.locale}>
        <QueryClientProvider client={queryClient}>
          <RuntimeContext.Provider value={runtime}>
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

function useRuntime(): Runtime {
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

export function useToast(): PushToast {
  const push = useContext(ToastContext)
  if (!push) {
    throw new Error('a remote page renders inside RemoteProvider')
  }
  return push
}
