import { permissions } from '@suite/api-client'
import type { Screen, ScreenSection } from '@suite/remote-kit'
import { RemotesPage } from './features/remotes/page'
import { RolesPage } from './features/roles/page'
import { SystemsPage } from './features/systems/page'
import { TenantsPage } from './features/tenants/page'
import { UsersPage } from './features/users/page'

export const sections: ScreenSection[] = [
  { id: 'organization', label: 'common:nav.organization' },
  { id: 'integrations', label: 'common:nav.integrations' },
]

/** Every screen of this remote, in nav order; the first one the caller may open is the remote's landing page. */
export const screens: Screen[] = [
  {
    id: 'tenants',
    path: '/tenants',
    section: 'organization',
    label: 'tenants:title',
    permission: permissions.platform.list_1,
    component: TenantsPage,
  },
  {
    id: 'users',
    path: '/users',
    section: 'organization',
    label: 'users:title',
    permission: permissions.platform.list,
    component: UsersPage,
  },
  {
    id: 'roles',
    path: '/roles',
    section: 'organization',
    label: 'roles:title',
    permission: permissions.platform.list_3,
    component: RolesPage,
  },
  {
    id: 'systems',
    path: '/systems',
    section: 'integrations',
    label: 'systems:title',
    permission: permissions.platform.list_2,
    component: SystemsPage,
  },
  {
    id: 'remotes',
    path: '/remotes',
    section: 'integrations',
    label: 'remotes:title',
    permission: permissions.platform.listRemotes,
    component: RemotesPage,
  },
]
