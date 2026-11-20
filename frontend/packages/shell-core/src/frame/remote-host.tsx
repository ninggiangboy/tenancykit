import type { ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { ErrorState, Skeleton } from '@suite/ui'
import { useQuery } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Component, type ErrorInfo, type ReactNode, Suspense, useMemo } from 'react'
import type { ConsoleRemote } from '../contracts'
import type { RemoteLoader } from '../remote-loader.types'
import { useFrame } from './frame-context'
import { NotFound } from './home'

/** Loads the remote the URL names, verified, and mounts it with its own basepath and navigation hook. */
export function RemoteHost({ remotes, loader }: { remotes: ConsoleRemote[]; loader: RemoteLoader }) {
  const { context, setNavigation, t } = useFrame()
  const params = useParams({ strict: false }) as { remote?: string }
  const remote = remotes.find((entry) => entry.name === params.remote)
  const query = useQuery({
    queryKey: ['remote', remote?.name, remote?.version],
    queryFn: () => loader.load(remote as ConsoleRemote),
    enabled: Boolean(remote),
    retry: false,
  })

  const name = remote?.name
  const remoteContext = useMemo<ShellContext | null>(
    () =>
      name
        ? {
            ...context,
            basepath: `/console/r/${name}`,
            registerNavigation: (sections) => {
              setNavigation(name, sections)
              return () => setNavigation(name, null)
            },
          }
        : null,
    [context, name, setNavigation],
  )

  if (!remote || !remoteContext) {
    return <NotFound />
  }
  if (query.isError) {
    return (
      <div className="p-6">
        <ErrorState
          title={t('shell.remoteFailed')}
          description={`${t('shell.remoteFailedHint')} ${String((query.error as Error).message)}`}
          onRetry={() => void query.refetch()}
        />
      </div>
    )
  }
  if (!query.data) {
    return <RemoteSkeleton />
  }

  return (
    <div className="contents" data-remote={remote.name} data-remote-version={remote.version}>
      <RemoteBoundary onRetry={() => void query.refetch()} label={t('shell.remoteFailed')}>
        <ShellProvider ctx={remoteContext}>
          <Suspense fallback={<RemoteSkeleton />}>
            <query.data.Root ctx={remoteContext} />
          </Suspense>
        </ShellProvider>
      </RemoteBoundary>
    </div>
  )
}

function RemoteSkeleton() {
  return (
    <div className="flex flex-col gap-2 p-6">
      <Skeleton width="30%" height={22} />
      <Skeleton width="55%" />
      <Skeleton width="100%" height={200} />
    </div>
  )
}

class RemoteBoundary extends Component<
  { children: ReactNode; onRetry: () => void; label: string },
  { error: Error | null }
> {
  override state = { error: null as Error | null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('the console refused a remote that threw on mount', error, info)
  }

  override render() {
    if (this.state.error) {
      return (
        <div className="p-6">
          <ErrorState
            title={this.props.label}
            description={this.state.error.message}
            onRetry={() => {
              this.setState({ error: null })
              this.props.onRetry()
            }}
          />
        </div>
      )
    }
    return this.props.children
  }
}
