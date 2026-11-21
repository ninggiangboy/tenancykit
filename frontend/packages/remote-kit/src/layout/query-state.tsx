import { Alert, Button, Skeleton } from '@suite/ui'
import type { ReactNode } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'
import { errorMessage } from '../runtime/errors'

/** The parts of a TanStack query a read inside a sheet or a tab needs. */
export interface QueryStateSource<Data> {
  isLoading: boolean
  isError: boolean
  error: unknown
  data: Data | undefined
  refetch: () => unknown
}

export interface QueryStateProps<Data> {
  query: QueryStateSource<Data>
  /** What counts as nothing to show; an empty array by default. */
  isEmpty?: (data: Data) => boolean
  skeletonHeight?: number
  empty: ReactNode
  children: (data: Data) => ReactNode
}

/**
 * A read has three outcomes and each one looks different: loading, failed (the server's sentence and Retry) and empty.
 * A failure is never drawn as empty. A refetch that fails keeps the last data on screen and puts the same alert above it.
 */
export function QueryState<Data>({ query, isEmpty, skeletonHeight = 96, empty, children }: QueryStateProps<Data>) {
  const t = useKitT()
  const { data } = query
  if (query.isLoading) {
    return <Skeleton width="100%" height={skeletonHeight} />
  }
  const failure = query.isError ? (
    <Alert
      tone="danger"
      title={t('state.sectionFailed')}
      action={
        <Button variant="outline" size="sm" icon="refresh" onClick={() => void query.refetch()}>
          {t('actions.retry')}
        </Button>
      }
    >
      {errorMessage(query.error, t('state.requestFailed'))}
    </Alert>
  ) : null
  if (data === undefined) {
    return failure ?? <>{empty}</>
  }
  const nothing = isEmpty ? isEmpty(data) : Array.isArray(data) && data.length === 0
  return (
    <div className="flex flex-col gap-2">
      {failure}
      {nothing ? empty : children(data)}
    </div>
  )
}
