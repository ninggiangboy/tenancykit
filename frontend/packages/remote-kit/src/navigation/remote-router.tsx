import type { NavSection, ShellContext } from '@suite/shell-sdk'
import { can, useShell } from '@suite/shell-sdk/react'
import { EmptyState, type UrlState, urlState } from '@suite/ui'
import {
  createRootRoute,
  createRoute,
  createRouter,
  Navigate,
  Outlet,
  useNavigate,
  useSearch,
} from '@tanstack/react-router'
import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { useKitT } from '../i18n/create-remote-i18n'
import { Forbidden, Page } from '../layout/page'
import { RemoteNavigation } from './remote-navigation'

/** One screen of a remote: its path, where it sits in the nav, who may open it and what renders it. */
export interface Screen {
  id: string
  path: string
  section: string
  /** A catalog key with its namespace, for example `customers:title`. */
  label: string
  /** The permission that lets a caller list this screen's records; without it the screen is hidden and forbidden. */
  permission?: string
  component: ComponentType<{ state: UrlState }>
}

/** A nav group; `label` is a catalog key with its namespace. */
export interface ScreenSection {
  id: string
  label?: string
}

function allowed(ctx: ShellContext, screen: Screen): boolean {
  return !screen.permission || can(ctx, screen.permission)
}

function useUrlState(): UrlState {
  const search = useSearch({ strict: false }) as Record<string, unknown>
  const navigate = useNavigate()
  return urlState(search, (next) => void navigate({ to: '.', search: next, replace: true }))
}

/** The sections the caller may see, labelled in the current language. */
function useSections(screens: Screen[], sections: ScreenSection[]): NavSection[] {
  const ctx = useShell()
  const { t } = useTranslation()
  return sections
    .map((section) => ({
      id: section.id,
      label: section.label ? t(section.label) : undefined,
      items: screens
        .filter((screen) => screen.section === section.id && allowed(ctx, screen))
        .map((screen) => ({ id: screen.id, label: t(screen.label), to: screen.path })),
    }))
    .filter((section) => section.items.length > 0)
}

function NotFound() {
  const t = useKitT()
  return <EmptyState icon="file" title={t('state.notFound')} description={t('state.notFoundHint')} />
}

/**
 * The remote's router on the shell's history: a layout that hands the shell its nav, the index redirect to the first
 * screen the caller may open, and one route per screen that refuses a caller without the screen's permission.
 */
export function createRemoteRouter(ctx: ShellContext, screens: Screen[], sections: ScreenSection[]) {
  function Layout() {
    return (
      <RemoteNavigation sections={useSections(screens, sections)}>
        <Outlet />
      </RemoteNavigation>
    )
  }

  function Index() {
    const shell = useShell()
    const first = screens.find((screen) => allowed(shell, screen))
    return first ? <Navigate to={first.path} replace /> : <Forbidden />
  }

  const root = createRootRoute({ component: Layout, notFoundComponent: NotFound })
  const index = createRoute({ getParentRoute: () => root, path: '/', component: Index })
  const routes = screens.map((screen) =>
    createRoute({
      getParentRoute: () => root,
      path: screen.path,
      component: function ScreenRoute() {
        const shell = useShell()
        const state = useUrlState()
        const { t } = useTranslation()
        // The header stays, so the person knows which screen refused them.
        if (!allowed(shell, screen)) {
          return (
            <Page title={t(screen.label)}>
              <Forbidden bare permission={screen.permission} />
            </Page>
          )
        }
        const Component = screen.component
        return <Component state={state} />
      },
    }),
  )

  return createRouter({
    routeTree: root.addChildren([index, ...routes]),
    history: ctx.history,
    basepath: ctx.basepath,
    defaultPreload: false,
  })
}
