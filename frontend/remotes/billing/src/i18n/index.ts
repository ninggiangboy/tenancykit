import type { Locale } from '@suite/shell-sdk'
import i18next, { type i18n } from 'i18next'
import { useTranslation } from 'react-i18next'
import chargesEn from './en/charges.json'
import commonEn from './en/common.json'
import couponsEn from './en/coupons.json'
import customersEn from './en/customers.json'
import invoicesEn from './en/invoices.json'
import paymentsEn from './en/payments.json'
import plansEn from './en/plans.json'
import pricingEn from './en/pricing.json'
import redemptionsEn from './en/redemptions.json'
import refundsEn from './en/refunds.json'
import reservationsEn from './en/reservations.json'
import stripeEn from './en/stripe.json'
import subscriptionsEn from './en/subscriptions.json'
import usageEn from './en/usage.json'
import walletsEn from './en/wallets.json'
import webhooksEn from './en/webhooks.json'
import chargesVi from './vi/charges.json'
import commonVi from './vi/common.json'
import couponsVi from './vi/coupons.json'
import customersVi from './vi/customers.json'
import invoicesVi from './vi/invoices.json'
import paymentsVi from './vi/payments.json'
import plansVi from './vi/plans.json'
import pricingVi from './vi/pricing.json'
import redemptionsVi from './vi/redemptions.json'
import refundsVi from './vi/refunds.json'
import reservationsVi from './vi/reservations.json'
import stripeVi from './vi/stripe.json'
import subscriptionsVi from './vi/subscriptions.json'
import usageVi from './vi/usage.json'
import walletsVi from './vi/wallets.json'
import webhooksVi from './vi/webhooks.json'

/** One resource set: every feature owns its own namespace. */
export const resources = {
  en: {
    common: commonEn,
    customers: customersEn,
    payments: paymentsEn,
    refunds: refundsEn,
    invoices: invoicesEn,
    subscriptions: subscriptionsEn,
    plans: plansEn,
    pricing: pricingEn,
    coupons: couponsEn,
    redemptions: redemptionsEn,
    wallets: walletsEn,
    reservations: reservationsEn,
    charges: chargesEn,
    usage: usageEn,
    webhooks: webhooksEn,
    stripe: stripeEn,
  },
  vi: {
    common: commonVi,
    customers: customersVi,
    payments: paymentsVi,
    refunds: refundsVi,
    invoices: invoicesVi,
    subscriptions: subscriptionsVi,
    plans: plansVi,
    pricing: pricingVi,
    coupons: couponsVi,
    redemptions: redemptionsVi,
    wallets: walletsVi,
    reservations: reservationsVi,
    charges: chargesVi,
    usage: usageVi,
    webhooks: webhooksVi,
    stripe: stripeVi,
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
