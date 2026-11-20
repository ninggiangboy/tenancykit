import { createRemoteI18n, type Translate, useNamespace } from '@suite/remote-kit'
import type { Locale } from '@suite/shell-sdk'
import type { i18n } from 'i18next'
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
export type { Translate }

/** This remote's catalogs plus the kit's, for the shell's locale. */
export function createI18n(locale: Locale): i18n {
  return createRemoteI18n(locale, resources)
}

/** The translator of one feature namespace. */
export function useT(namespace: Namespace): Translate {
  return useNamespace(namespace)
}
