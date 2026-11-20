import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { createElement, type ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { useViewCounts } from './use-view-counts'

const views = [
  { id: 'all', label: 'All' },
  { id: 'open', label: 'Open' },
]

describe('useViewCounts', () => {
  it('returns the total of every view', async () => {
    const client = new QueryClient()
    const optionsFor = (params: Record<string, string>) => ({
      queryKey: ['count', params],
      queryFn: async () => ({ page: { total: 7 } }),
    })
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)

    const { result } = renderHook(() => useViewCounts(views, optionsFor), { wrapper })

    await waitFor(() => expect(result.current).toEqual({ all: 7, open: 7 }))
  })
})
