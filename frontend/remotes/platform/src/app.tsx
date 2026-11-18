import { defineRemote, SDK_MAJOR, type ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { useMemo } from 'react'
import { RemoteProvider } from './lib/runtime'
import { createRemoteRouter } from './routes'
import './index.css'
import { RouterProvider } from '@tanstack/react-router'

function Root({ ctx }: { ctx: ShellContext }) {
  const router = useMemo(() => createRemoteRouter(ctx), [ctx.history, ctx.basepath])
  return (
    <ShellProvider ctx={ctx}>
      <RemoteProvider ctx={ctx}>
        <RouterProvider router={router} />
      </RemoteProvider>
    </ShellProvider>
  )
}

export default defineRemote({ name: 'platform', sdkMajor: SDK_MAJOR, Root })
