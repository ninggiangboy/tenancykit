# Multi-tenancy and Data Isolation

## Tenant model

One Platform manages many Tenants. Each Tenant is an independent business space.

- Each Tenant has a unique `tenant-code`.
- Each Tenant has its own data, configuration and Billing operations.
- Data is never accessible across Tenants.
- Wallet, Ledger, Invoice, Payment, Refund, Subscription and Customer all belong to a Tenant scope.
- The same email can exist in several Tenants as independent accounts.
- A User of one Tenant has no implicit access to another Tenant.
- A Tenant can integrate one or more Tenant Systems.

## Isolation strategy

Shared Database, Shared Schema, Tenant ID — per service.

Every service owns exactly one database, one owner role (Flyway migrates as it) and one application role (the
service connects as it, it owns nothing and cannot bypass Row-Level Security). `revoke connect on database ... from
public` closes the default every role shares, so one service's application role cannot open another service's
database even though both roles live on the same PostgreSQL server. `ops/postgres/init/00-databases.sh` creates the
three databases that way, and `ops/smoke/isolation.sh` proves a cross-database connect is refused.

- Tenant-scoped business tables carry a `tenant_id` column.
- Every data query is restricted by the Tenant Context.
- Authorization checks the Tenant Scope before allowing access.
- Relationships must make cross-Tenant references impossible, using composite foreign keys for important relations.
- Unique constraints include `tenant_id` whenever the identifier is scoped to a Tenant.
- PostgreSQL Row-Level Security is a further layer: the tenant is set once per transaction inside the transaction boundary, so no query can run outside a tenant scope.
- The migration owner owns every table; the application connects as a separate role that owns nothing and cannot bypass Row-Level Security. Policies are forced, so they also apply to the owner. A connection or code path that forgets the tenant fails closed and returns no rows.
- Two kinds of table exist. Platform-owned tables (the Tenant list and its hostname mapping) are readable without a tenant, because the hostname has to be resolved before any Tenant Context exists, and they hold no financial data. Every other table carries `tenant_id` and enables Row-Level Security through one shared migration helper; a build test fails when a tenant-scoped table does not.
- A request or job acts in exactly one scope: the Platform, or one Tenant. A Platform-scope transaction sets no tenant, so it cannot read tenant-scoped rows. Platform Administrators read Tenant data only by acting in that Tenant's explicit, audited context.
- The Tenant Context reaches the domain as a value object; a raw `tenant_id` is never threaded through business code.

### Replicas keep the same guarantees

No database is shared, so Platform data reaches an app as a replica the app owns. The replica tables keep the names
and shapes they had in Billing (`tenants`, `tenant_domains`, `tenant_systems`, `user_grants`, `app_entitlements`), so
the foreign keys and Row-Level Security that already governed them are unchanged: a Billing table that points at
`tenants` still does, and a replica row is still tenant-scoped. `core-platform-sync` writes the replica from Platform's
snapshot and from `platform.events`; the app enforces from the replica exactly as it would from its own tables. A
replica lag therefore delays enforcement, not isolation: a tenant that Platform deactivated is still isolated, just
still reachable until the consumer catches up, and `tenant_systems.status` is re-checked on every request so a
disabled system stops working at once.

Billing is the only app that keeps a replica. The sample tenant system holds none: its users, roles and sessions are
its own, and it reaches Billing with a Platform-issued suite token instead of enforcing Platform's grants.

## Tenant resolution and domains

Each Tenant is reached through a subdomain.

| Domain | Scope |
|---|---|
| `platform.base-domain.com` | Platform Administration |
| `tenant-a.base-domain.com` | Tenant A |
| `tenant-b.base-domain.com` | Tenant B |

- The Tenant is resolved automatically from the request hostname.
- An unknown hostname, or one that belongs to a deactivated Tenant, gets one generic not-found answer that does not reveal whether the Tenant exists.
- A Tenant Context is built once and carried through every processing layer.
- Background Jobs and Event Consumers carry the Tenant Context too.
- A `tenant_id` supplied by a client is never trusted to determine access rights.
- Domain Mapping is designed in so Custom Domains can be added later.

## Related

- Authentication and tenant-scoped identity: [authentication-and-authorization.md](authentication-and-authorization.md).
- Session isolation rules: [security-and-reliability.md](security-and-reliability.md).
