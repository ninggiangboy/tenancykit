import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { I18nextProvider } from 'react-i18next'
import { describe, expect, it, vi } from 'vitest'
import { createRemoteI18n } from '../i18n/create-remote-i18n'
import { ERROR_TOAST_MS, useAction } from './use-action'

const toast = vi.fn()
vi.mock('./remote-provider', () => ({ useToast: () => toast }))

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } })
  const i18n = createRemoteI18n('en', { en: { common: {} }, vi: { common: {} } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </I18nextProvider>
  )
  return { client, wrapper }
}

describe('useAction', () => {
  it('keeps a failure on screen for ten seconds, with the server sentence', async () => {
    toast.mockClear()
    const { wrapper } = setup()
    const { result } = renderHook(() => useAction(() => Promise.reject({ detail: 'Invoice is already void' })), {
      wrapper,
    })
    await act(async () => {
      result.current.mutate(undefined)
    })
    await waitFor(() => expect(toast).toHaveBeenCalled())
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Invoice is already void', tone: 'danger', duration: ERROR_TOAST_MS }),
    )
    expect(ERROR_TOAST_MS).toBe(10_000)
  })

  it('names what was being done when the caller supplies a title for the failure', async () => {
    toast.mockClear()
    const { wrapper } = setup()
    const { result } = renderHook(
      () =>
        useAction((_: string) => Promise.reject({ detail: 'Nope' }), {
          errorTitle: (error, name) => `Could not change ${name}: ${(error as { detail: string }).detail}`,
        }),
      { wrapper },
    )
    await act(async () => {
      result.current.mutate('Billing')
    })
    await waitFor(() => expect(toast).toHaveBeenCalled())
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ title: 'Could not change Billing: Nope' }))
  })

  it('with invalidate, refetches only those queries and leaves another mounted query alone', async () => {
    const { client, wrapper } = setup()
    const own = vi.fn(async () => 'own')
    const other = vi.fn(async () => 'other')
    const { result } = renderHook(
      () => {
        useQuery({ queryKey: [{ _id: 'apps' }], queryFn: own })
        useQuery({ queryKey: [{ _id: 'invoices' }], queryFn: other })
        return useAction(async () => 'done', { invalidate: [[{ _id: 'apps' }]] })
      },
      { wrapper },
    )
    await waitFor(() => expect(own).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(other).toHaveBeenCalledTimes(1))
    await act(async () => {
      result.current.mutate(undefined)
    })
    await waitFor(() => expect(own).toHaveBeenCalledTimes(2))
    expect(other).toHaveBeenCalledTimes(1)
    client.clear()
  })

  it('without invalidate, refetches every mounted query', async () => {
    const { wrapper } = setup()
    const first = vi.fn(async () => 1)
    const second = vi.fn(async () => 2)
    const { result } = renderHook(
      () => {
        useQuery({ queryKey: ['a'], queryFn: first })
        useQuery({ queryKey: ['b'], queryFn: second })
        return useAction(async () => 'done')
      },
      { wrapper },
    )
    await waitFor(() => expect(second).toHaveBeenCalledTimes(1))
    await act(async () => {
      result.current.mutate(undefined)
    })
    await waitFor(() => expect(first).toHaveBeenCalledTimes(2))
    expect(second).toHaveBeenCalledTimes(2)
  })
})
