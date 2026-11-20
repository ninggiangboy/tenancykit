import type { Locale, ShellContext } from '@suite/shell-sdk'
import { ShellProvider } from '@suite/shell-sdk/react'
import { RouterProvider } from '@tanstack/react-router'
import type { i18n } from 'i18next'
import { useMemo } from 'react'
import { RemoteProvider } from '../runtime/remote-provider'
import { createRemoteRouter, type Screen, type ScreenSection } from './remote-router'

export interface RemoteRootProps {
  ctx: ShellContext
  createI18n: (locale: Locale) => i18n
  screens: Screen[]
  sections: ScreenSection[]
}

/** Everything a remote's `Root` mounts: the shell context, the runtime and the router over its screens. */
export function RemoteRoot({ ctx, createI18n, screens, sections }: RemoteRootProps) {
  const router = useMemo(
    () => createRemoteRouter(ctx, screens, sections),
    [ctx.history, ctx.basepath, screens, sections],
  )
  return (
    <ShellProvider ctx={ctx}>
      <RemoteProvider ctx={ctx} createI18n={createI18n}>
        <RouterProvider router={router} />
      </RemoteProvider>
    </ShellProvider>
  )
}
