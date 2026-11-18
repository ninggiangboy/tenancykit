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
      className="rounded-md px-2 py-1.5 text-small text-muted-foreground hover:bg-accent hover:text-foreground"
    >
      {label}
    </Link>
  )
}

function Layout() {
  const tc = useT('common')
  const customers = useT('customers')
  const payments = useT('payments')
  const refunds = useT('refunds')
  const invoices = useT('invoices')
  const subscriptions = useT('subscriptions')
  const plans = useT('plans')
  const pricing = useT('pricing')
  const coupons = useT('coupons')
  const redemptions = useT('redemptions')
  const wallets = useT('wallets')
  const reservations = useT('reservations')
  const charges = useT('charges')
  const usage = useT('usage')
  const webhooks = useT('webhooks')
  const stripe = useT('stripe')
  return (
    <div className="flex flex-col">
      <nav
        aria-label={tc('nav.sections')}
        className="flex flex-wrap items-center gap-1 border-b border-border px-4 py-2"
      >
        <Section permission={permissions.billing.list_1} to="/customers" label={customers('title')} />
        <Section permission={permissions.billing.payments_1} to="/payments" label={payments('title')} />
        <Section permission={permissions.billing.refunds_1} to="/refunds" label={refunds('title')} />
        <Section permission={permissions.billing.invoices} to="/invoices" label={invoices('title')} />
        <Section permission={permissions.billing.subscriptions} to="/subscriptions" label={subscriptions('title')} />
        <Section permission={permissions.billing.listPlans} to="/plans" label={plans('title')} />
        <Section permission={permissions.billing.estimate} to="/pricing" label={pricing('title')} />
        <Section permission={permissions.billing.coupons} to="/coupons" label={coupons('title')} />
        <Section permission={permissions.billing.redemptionsOfWallet} to="/redemptions" label={redemptions('title')} />
        <Section permission={permissions.billing.wallets} to="/wallets" label={wallets('title')} />
        <Section permission={permissions.billing.reservations} to="/reservations" label={reservations('title')} />
        <Section permission={permissions.billing.charges} to="/charges" label={charges('title')} />
        <Section permission={permissions.billing.history} to="/usage" label={usage('title')} />
        <Section permission={permissions.billing.listEndpoints} to="/webhooks" label={webhooks('title')} />
        <Section permission={permissions.billing.getConfiguration} to="/stripe" label={stripe('title')} />
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
    throw redirect({ to: '/customers' })
  },
})

function page(path: string, Component: (props: { state: UrlState }) => React.ReactElement) {
  return createRoute({
    getParentRoute: () => root,
    path,
    component: () => <Component state={useRemoteSearch()} />,
  })
}

const routes = [
  page('/customers', CustomersPage),
  page('/payments', PaymentsPage),
  page('/refunds', RefundsPage),
  page('/invoices', InvoicesPage),
  page('/subscriptions', SubscriptionsPage),
  page('/plans', PlansPage),
  page('/pricing', PricingPage),
  page('/coupons', CouponsPage),
  page('/redemptions', RedemptionsPage),
  page('/wallets', WalletsPage),
  page('/reservations', ReservationsPage),
  page('/charges', ChargesPage),
  page('/usage', UsagePage),
  page('/webhooks', WebhooksPage),
  createRoute({ getParentRoute: () => root, path: '/stripe', component: StripePage }),
]

const routeTree = root.addChildren([index, ...routes])

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
