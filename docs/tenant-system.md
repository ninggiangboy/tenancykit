# Sample Tenant System

A complete reference system that verifies integration with the suite. It follows a Cloud Resource Management model and simulates a platform that rents resources. It deploys independently of Billing but needs Billing for every financial function, and it authenticates to Billing with a Platform-issued suite token.

## The Platform token flow

The tenant holds a Tenant System credential Platform issued: a client id and a secret, created through Platform's
tenant-system credential management and shown once.

1. On the first call to Billing, `ClientCredentialsTokenProvider` from the shared `core-feign` module asks Platform
   for a token: `POST {platform}/oauth/token`, HTTP Basic `client-id:secret`, `grant_type=client_credentials`,
   `audience=billing`.
2. Platform answers a 10-minute JWT whose `scope` holds the `billing:*` codes the credential was granted.
3. The provider caches it until 60 seconds before expiry. A burst of callers shares one refresh: the first fetches,
   the rest wait and read the new token.
4. Every Billing call carries `Authorization: Bearer <token>`. On a 401 the provider drops the cached token, fetches a
   fresh one and retries exactly once; a second refusal surfaces as an authentication error.
5. Billing derives the Tenant System from the token's `tenant_system_id` and the Tenant from `tenant_id`; the tenant
   never sends either.

`tenant.billing.api-key` and `tenant.billing.tenant-system-id` no longer exist. The token is never logged. The
webhook endpoint registration and Billing's signed deliveries are unchanged: the registration call uses the same token
provider, and deliveries are still verified with the endpoint's signing secret.

## Business functions

- User Registration and Authentication.
- User account management.
- Business Role and Permission management.
- Customer management.
- Customer Mapping with Billing.
- Resource Catalog management.
- Resource Order management.
- Resource Lifecycle management.
- Execution and Background Workload management.
- Resource Usage tracking.
- Management of Billing-related transactions.
- Billing Webhook handling.
- A Customer Portal and Resource Management UI.

## Resource types

| Resource | Illustrated pricing model |
|---|---|
| Compute Instance | Time-based |
| Storage | Usage-based |
| Bandwidth | Usage-based |
| Background Workload | Workload-based |
| API Operation | Event-based |
| Managed Service | Subscription |
| Cloud Package | Hybrid |

## Integration scenarios it must demonstrate

1. User registration and creating a Customer Mapping.
2. Fetching a Pricing Plan and performing Cost Estimation.
3. Checking the Wallet and performing a Reservation.
4. Creating a Resource Order and putting it into the Execution Queue.
5. Recording Usage during use.
6. Performing a Charge when cost is incurred.
7. Releasing the Reservation and updating the remaining budget.
8. Topping up through Stripe.
9. Supporting Stripe Direct and Stripe Connect.
10. Viewing Invoices and transaction history.
11. Handling Webhooks when Billing state changes.
12. Using Subscriptions and combined pricing models.
13. Supporting both Prepaid and Postpaid.
14. Testing data isolation between multiple Tenants.
15. Testing the same email with independent accounts in several Tenants.

## Responsibility

It owns Resources, Orders, Execution and its own users; it decides what to do with Billing's state changes. It depends only on the technical core and the contracts: `checkNoBillingDependency` fails a build that depends on Billing or Platform, and an ArchUnit rule fails one that names their packages. See the division rules in [overview-and-scope.md](overview-and-scope.md) and the event contract in [webhooks-and-events.md](webhooks-and-events.md).

## Stack

Same backend and frontend toolchain as Billing, see [architecture.md](architecture.md). It builds against the same technical core as the other services (`core/`): `UnitOfWork`, `Clock` and `IdGenerator`. It plugs no `TransactionScopeHook` into `UnitOfWork` because it has no row-level security.

## Module layout

`tenant/settings.gradle.kts` lists the modules; each is a Gradle project with `api`, `domain`,
`application` and `adapter` source sets. Packages are grouped by feature and then by role, exactly as
in Billing:

| Module | Features | Holds |
|---|---|---|
| `identity` | `user`, `session`, `account` | Accounts and credentials, opaque sessions, the roles and permissions in `api.security`, and the account listener contract in `api.account` |
| `customer` | `customer`, `wallet`, `billing` | Customer mapping, wallet mode settings, Billing sessions, invoices, payments and top-ups |
| `catalog` | `plan` | The offer catalog and its binding to Billing pricing plans |
| `resource` | `order` | Orders, the execution queue the module owns, and the order and queue api |
| `usage` | `usage` | Locally recorded usage forwarded to Billing |
| `execution` | `queue` | The worker's application layer only; the queue table is owned by `resource` |
| `webhook` | `event` | Inbound Billing webhooks: verification, deduplication and the tenant's policy |
| `billing-client` | `client` | The Billing REST contracts and their HTTP adapter, and the Platform token provider that authenticates them |
| `apps:app-boot` | | Spring wiring, security, jobs, the HTTP edge and the Flyway migrations |
| `libs:shared-core` | | Plain-Java types every module may use |
| `libs:jooq-schema` | | jOOQ classes generated from the migrations and committed |
| `testing:arch-fixtures` | | Deliberately violating classes that prove each ArchUnit rule can fail |

Within a module, `application` is split into `command` (use cases), `query` (views, query classes and
read ports), `port` (write ports and their carriers) and `service` (collaborators and the
`<Interface>Service` that implements an `api` interface for another module). An adapter is
`adapter.<feature>.<technology>`; a feature's write and read repositories share its `persistence`
package. `api` packages are named for the capability a caller uses (`lookup`, `execution`,
`recording`, `security`, `account`).

## Persistence and the read/write split

The sample persists with jOOQ, not Spring Data JDBC, and splits command from query:

- Aggregates, write ports and `Jooq*Repository` on the write side; immutable `*View` records,
  `*ReadRepository` ports in `query` and `Jooq*ReadRepository` projections on the read side. A read
  never builds an aggregate, and a write port never lists for a caller.
- `libs:jooq-schema` is generated from the Flyway migrations and committed, so a normal compile
  needs no database. `JooqSchemaTest` regenerates it in a throwaway PostgreSQL and fails when the
  committed code drifts; `-Djooq.update=true` rewrites it.
- Special SQL stays explicit: conflict-ignoring inserts (`on conflict do nothing`), upserts, guarded
  updates and the queue's `for update skip locked ... returning` are statements, never `store()`.
- `CreateOrderUseCase` opens core's `UnitOfWork` around the order and its queue row, so both commit
  or neither does. `execution` reaches the queue only through `resource.api.execution.ExecutionQueue`.

Decisions:

- **D1**: `resource` owns `execution_queue`. `execution` has no persistence and reaches the queue
  through `resource.api.execution.ExecutionQueue`; the dependency direction `execution` to `resource`
  already existed, so no Gradle cycle appears.
- **D2**: the `billing-client` module keeps its name, because renaming it to `billing` would make
  `checkNoBillingDependency` flag the sample's own project path. Only `webhook-receiver` was renamed
  to `webhook`.
- **D3**: the sample has its own ArchUnit rule set and fixture module, copied from Billing's generic
  rules and kept free of Billing-specific ones. `core/` is not touched.
- **D4**: Spring Modulith verifies the modules and writes their documentation; `api` sub-packages are
  exposed as named interfaces from `package-info` files in the adapter source set.

## Test pyramid

- Unit tests on the plain-Java application layer with in-memory fakes.
- A `Jooq*RepositoryIntegrationTest` per feature, against a Testcontainers PostgreSQL: round trips,
  conflict-ignoring inserts, upserts, guarded updates, the bulk delete, and a two-claimer test that asserts
  disjoint queue batches and incremented attempts. Each class proves the feature's write repository and its
  `Jooq*ReadRepository` together, so a read projection is checked against the same migrated schema rather than
  in a class of its own.
- One `*HttpIntegrationTest` per controller, with the full response body asserted, plus the signed,
  forged, stale and duplicate webhook deliveries. Billing is replaced by `FakeBilling`; the tests
  never call the network.
- `JooqSchemaTest` (schema drift), `ArchitectureTest` plus `ArchitectureMetaTest` (every rule fails on
  its fixture), `ModulithVerificationTest` and `ApplicationContextTest`.

Integration tests need Docker. See `tenant/README.md` for the commands.
