import type { Locale } from '@suite/shell-sdk'
import i18next, { type i18n } from 'i18next'
import { useTranslation } from 'react-i18next'
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

const namespaces = Object.keys(resources.en) as Namespace[]

/**
 * One instance per remote, created from the shell's locale. A missing key throws while tests run, so a page rendered
 * in both languages cannot quietly ship a raw key.
 */
export function createRemoteI18n(locale: Locale): i18n {
  const instance = i18next.createInstance()
  const test = import.meta.env.MODE === 'test'
  void instance.init({
    lng: locale,
    fallbackLng: 'en',
    ns: namespaces,
    defaultNS: 'common',
    resources,
    interpolation: { escapeValue: false },
    initAsync: false,
    saveMissing: test,
    missingKeyHandler: test
      ? (languages: readonly string[], namespace: string, key: string) => {
          throw new Error(`missing i18n key "${namespace}:${key}" for ${languages.join(',')}`)
        }
      : undefined,
  })
  return instance
}

export type Translate = (key: string, values?: Record<string, unknown>) => string

/** The translator of one feature namespace. */
export function useT(namespace: Namespace): Translate {
  const { t } = useTranslation(namespace)
  return t as unknown as Translate
}
