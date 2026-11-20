import { listQuery } from '@suite/api-client'
import { type UseQueryOptions, useQueries } from '@tanstack/react-query'
import type { PageInfo, ViewSpec } from './list-spec'

/** The generated list options differ in their query key, so a caller hands over an object the hook reads `page.total` from. */
export type ViewCountOptions = (params: Record<string, string>) => unknown

/** Each saved view's total, one cheap request per view: limit 1 with the full page info. */
export function useViewCounts(
  views: ViewSpec[] | undefined,
  optionsFor: ViewCountOptions,
): Record<string, number | undefined> {
  const list = views ?? []
  const results = useQueries({
    queries: list.map(
      (view) =>
        optionsFor(listQuery({ filters: view.filters ?? [], limit: 1, pageInfo: 'full' })) as UseQueryOptions<{
          page?: PageInfo | null
        }>,
    ),
  })
  return Object.fromEntries(list.map((view, index) => [view.id, results[index]?.data?.page?.total ?? undefined]))
}
