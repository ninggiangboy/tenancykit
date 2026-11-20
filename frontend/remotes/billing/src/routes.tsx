import { permissions } from '@suite/api-client'
import type { Screen, ScreenSection } from '@suite/remote-kit'
import { ChargesPage } from './features/charges/page'
import { CouponsPage } from './features/coupons/page'
import { CustomersPage } from './features/customers/page'
import { InvoicesPage } from './features/invoices/page'
import { PaymentsPage } from './features/payments/page'
import { PlansPage } from './features/plans/page'
import { PricingPage } from './features/pricing/page'
import { RedemptionsPage } from './features/redemptions/page'
import { RefundsPage } from './features/refunds/page'
import { ReservationsPage } from './features/reservations/page'
import { StripePage } from './features/stripe/page'
import { SubscriptionsPage } from './features/subscriptions/page'
import { UsagePage } from './features/usage/page'
import { WalletsPage } from './features/wallets/page'
import { WebhooksPage } from './features/webhooks/page'

export const sections: ScreenSection[] = [
  { id: 'money', label: 'common:nav.money' },
  { id: 'pricing', label: 'common:nav.pricing' },
  { id: 'wallets', label: 'common:nav.wallets' },
  { id: 'integrations', label: 'common:nav.integrations' },
]

const p = permissions.billing

/** Every screen of Billing, in nav order; the first one the caller may open is the landing page. */
export const screens: Screen[] = [
  {
    id: 'customers',
    path: '/customers',
    section: 'money',
    label: 'customers:title',
    permission: p.list_1,
    component: CustomersPage,
  },
  {
    id: 'invoices',
    path: '/invoices',
    section: 'money',
    label: 'invoices:title',
    permission: p.invoices,
    component: InvoicesPage,
  },
  {
    id: 'payments',
    path: '/payments',
    section: 'money',
    label: 'payments:title',
    permission: p.payments_1,
    component: PaymentsPage,
  },
  {
    id: 'refunds',
    path: '/refunds',
    section: 'money',
    label: 'refunds:title',
    permission: p.refunds_1,
    component: RefundsPage,
  },
  {
    id: 'subscriptions',
    path: '/subscriptions',
    section: 'money',
    label: 'subscriptions:title',
    permission: p.subscriptions,
    component: SubscriptionsPage,
  },
  {
    id: 'plans',
    path: '/plans',
    section: 'pricing',
    label: 'plans:title',
    permission: p.listPlans,
    component: PlansPage,
  },
  {
    id: 'pricing',
    path: '/pricing',
    section: 'pricing',
    label: 'pricing:title',
    permission: p.estimate,
    component: PricingPage,
  },
  {
    id: 'coupons',
    path: '/coupons',
    section: 'pricing',
    label: 'coupons:title',
    permission: p.coupons,
    component: CouponsPage,
  },
  {
    id: 'redemptions',
    path: '/redemptions',
    section: 'pricing',
    label: 'redemptions:title',
    permission: p.redemptionsOfWallet,
    component: RedemptionsPage,
  },
  {
    id: 'wallets',
    path: '/wallets',
    section: 'wallets',
    label: 'wallets:title',
    permission: p.wallets,
    component: WalletsPage,
  },
  {
    id: 'reservations',
    path: '/reservations',
    section: 'wallets',
    label: 'reservations:title',
    permission: p.reservations,
    component: ReservationsPage,
  },
  {
    id: 'charges',
    path: '/charges',
    section: 'wallets',
    label: 'charges:title',
    permission: p.charges,
    component: ChargesPage,
  },
  {
    id: 'usage',
    path: '/usage',
    section: 'wallets',
    label: 'usage:title',
    permission: p.history,
    component: UsagePage,
  },
  {
    id: 'webhooks',
    path: '/webhooks',
    section: 'integrations',
    label: 'webhooks:title',
    permission: p.listEndpoints,
    component: WebhooksPage,
  },
  {
    id: 'stripe',
    path: '/stripe',
    section: 'integrations',
    label: 'stripe:title',
    permission: p.getConfiguration,
    component: StripePage,
  },
]
