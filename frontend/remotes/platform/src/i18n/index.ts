import { createRemoteI18n, type Translate, useNamespace } from '@suite/remote-kit'
import type { Locale } from '@suite/shell-sdk'
import type { i18n } from 'i18next'
import commonEn from './en/common.json'
import remotesEn from './en/remotes.json'
import rolesEn from './en/roles.json'
import systemsEn from './en/systems.json'
import tenantsEn from './en/tenants.json'
import usersEn from './en/users.json'
import commonVi from './vi/common.json'
import remotesVi from './vi/remotes.json'
import rolesVi from './vi/roles.json'
import systemsVi from './vi/systems.json'
import tenantsVi from './vi/tenants.json'
import usersVi from './vi/users.json'

/** One resource set: every feature owns its own namespace. */
export const resources = {
  en: {
    common: commonEn,
    tenants: tenantsEn,
    users: usersEn,
    roles: rolesEn,
    systems: systemsEn,
    remotes: remotesEn,
  },
  vi: {
    common: commonVi,
    tenants: tenantsVi,
    users: usersVi,
    roles: rolesVi,
    systems: systemsVi,
    remotes: remotesVi,
  },
} as const

export type Namespace = keyof (typeof resources)['en']
export type { Translate }

/** This remote's catalogs plus the kit's, for the shell's locale. */
export function createI18n(locale: Locale): i18n {
  return createRemoteI18n(locale, resources)
}

/** The translator of one feature namespace. */
export function useT(namespace: Namespace): Translate {
  return useNamespace(namespace)
}
