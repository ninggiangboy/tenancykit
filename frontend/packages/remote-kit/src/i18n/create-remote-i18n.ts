import type { Locale } from '@suite/shell-sdk'
import i18next, { type i18n } from 'i18next'
import { useTranslation } from 'react-i18next'
import { type KitKey, kitEn, kitVi } from './kit-catalog'

export type Catalog = Record<string, string>
export type RemoteResources = { en: Record<string, Catalog>; vi: Record<string, Catalog> }

/** A translator for one namespace; values interpolate `{{name}}`. */
export type Translate = (key: string, values?: Record<string, unknown>) => string

export const KIT_NAMESPACE = 'kit'

/**
 * One i18next instance per remote, from the shell's locale, with the kit's namespace added to the remote's own. A
 * missing key throws while tests run, so a page rendered in both languages cannot quietly ship a raw key.
 */
export function createRemoteI18n(locale: Locale, resources: RemoteResources): i18n {
  const instance = i18next.createInstance()
  const test = import.meta.env?.MODE === 'test'
  const merged = {
    en: { ...resources.en, [KIT_NAMESPACE]: kitEn },
    vi: { ...resources.vi, [KIT_NAMESPACE]: kitVi },
  }
  void instance.init({
    lng: locale,
    fallbackLng: 'en',
    ns: Object.keys(merged.en),
    defaultNS: 'common',
    resources: merged,
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

/** The translator of one namespace of the remote's catalog. */
export function useNamespace(namespace: string): Translate {
  const { t } = useTranslation(namespace)
  return t as unknown as Translate
}

/** The kit's own strings. */
export function useKitT(): (key: KitKey, values?: Record<string, unknown>) => string {
  return useNamespace(KIT_NAMESPACE)
}
