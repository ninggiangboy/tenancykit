import { permissions } from '@suite/api-client'
import type { ShellContext } from '@suite/shell-sdk'
import { can, useShell } from '@suite/shell-sdk/react'
import { EmptyState, type UrlState, urlState } from '@suite/ui'
import {
  createRootRoute,
  createRoute,
  createRouter,
  Link,
  Outlet,
  RouterProvider,
  redirect,
  useNavigate,
  useSearch,
} from '@tanstack/react-router'
import { RemotesPage } from './features/remotes/page'
import { RolesPage } from './features/roles/page'
import { SystemsPage } from './features/systems/page'
import { TenantsPage } from './features/tenants/page'
import { UsersPage } from './features/users/page'
import { useT } from './i18n'

function useRemoteSearch(): UrlState {
  const search = useSearch({ strict: false }) as Record<string, unknown>
  const navigate = useNavigate()
  return urlState(search, (next) => void navigate({ to: '.', search: next, replace: true }))
}

function Section({ permission, to, label }: { permission: string; to: string; label: string }) {
  const ctx = useShell()
  if (!can(ctx, permission)) return null
  return (
    <Link
      to={to}
      activeProps={{ className: 'bg-accent text-foreground' }}
      className="rounded-md px-2.5 py-1.5 text-body-strong text-muted-foreground hover:bg-accent hover:text-foreground"
    >
      {label}
    </Link>
  )
}

function Layout() {
  const tc = useT('common')
  const tenants = useT('tenants')
  const users = useT('users')
  const roles = useT('roles')
  const systems = useT('systems')
  const remotes = useT('remotes')
  return (
    <div className="flex flex-col">
      <nav
        aria-label={tc('nav.sections')}
        className="flex flex-wrap items-center gap-1 border-b border-border px-4 py-2"
      >
        <Section permission={permissions.platform.list_1} to="/tenants" label={tenants('title')} />
        <Section permission={permissions.platform.list} to="/users" label={users('title')} />
        <Section permission={permissions.platform.list_3} to="/roles" label={roles('title')} />
        <Section permission={permissions.platform.list_2} to="/systems" label={systems('title')} />
        <Section permission={permissions.platform.listRemotes} to="/remotes" label={remotes('title')} />
      </nav>
      <Outlet />
    </div>
  )
}

function NotFound() {
  const t = useT('common')
  return <EmptyState icon="file" title={t('state.notFound')} description={t('state.notFoundHint')} />
}

const root = createRootRoute({ component: Layout, notFoundComponent: NotFound })

const index = createRoute({
  getParentRoute: () => root,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/tenants' })
  },
})

const tenants = createRoute({
  getParentRoute: () => root,
  path: '/tenants',
  component: () => <TenantsPage state={useRemoteSearch()} />,
})

const users = createRoute({
  getParentRoute: () => root,
  path: '/users',
  component: () => <UsersPage state={useRemoteSearch()} />,
})

const roles = createRoute({
  getParentRoute: () => root,
  path: '/roles',
  component: () => <RolesPage state={useRemoteSearch()} />,
})

const systems = createRoute({
  getParentRoute: () => root,
  path: '/systems',
  component: () => <SystemsPage state={useRemoteSearch()} />,
})

const remotes = createRoute({
  getParentRoute: () => root,
  path: '/remotes',
  component: () => <RemotesPage state={useRemoteSearch()} />,
})

const routeTree = root.addChildren([index, tenants, users, roles, systems, remotes])

export function createRemoteRouter(ctx: ShellContext) {
  return createRouter({
    routeTree,
    history: ctx.history,
    basepath: ctx.basepath,
    defaultPreload: false,
  })
}

export function RemoteRoutes({ ctx }: { ctx: ShellContext }) {
  return <RouterProvider router={createRemoteRouter(ctx)} />
}
