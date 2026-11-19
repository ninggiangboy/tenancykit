# TenancyKit

Documentation lives in [docs/](docs/README.md). Code lives in:

| Directory | Contents |
|---|---|
| `backend/services/platform/` | Platform service (Gradle multi-module, Spring Boot): tenants, users, centralized RBAC, app entitlement, tenant systems and the suite's tokens |
| `backend/services/billing/` | Billing app (Gradle multi-module, Spring Boot): the eight billing contexts, replicating the Platform data it enforces |
| `backend/services/mailer/` | Mailer service (Gradle multi-module, Spring Boot): consumes `email.send` commands and delivers already-rendered email through SESv2 or SMTP, with a per-recipient delivery ledger |
| `backend/services/notification/` | Notification service: the suite's in-app inbox and transactional email for Platform users, a replica consumer that publishes `email.send` |
| `backend/services/mailroom/` | Mailroom app: a Tenant's own templates, receivers, groups, segments, campaigns and tracking, a replica consumer that publishes `email.send` and consumes `email-delivery.changed` |
| `backend/services/survey/` | Survey app: a Tenant's surveys, immutable versions, links, visits, responses, uploads, analytics and signed webhooks; a replica consumer that publishes `survey.events` and sends no message |
| `backend/services/attribution/` | Attribution app: a Tenant's campaigns, tracked links, the redirect and its signed click id, clicks, page views, sessions and conversions, the credit each click receives under a per-campaign model, and advertising cost entered by hand or imported from Google Ads and Meta Ads |
| `backend/services/booking/` | Booking app: a Tenant's bookable resources, weekly hours and exceptions, groups, interval and capacity offerings, the two inventory engines behind one `Booking` lifecycle, availability, reschedules, series, a waitlist and a waiting room, the public customer gate and signed webhooks; a replica consumer that sends no message and touches no money |
| `backend/services/customer/` | Customer app: a Tenant's own customers, orders with line items, returns, activities, custom fields, tags and consent, the 360 profile, search and CSV export, and four signed webhooks; a replica consumer that declares no exchange and never reads another app's data |
| `backend/services/loyalty/` | Loyalty app: a Tenant's points program, its program and tier settings and promotions, members, the lot-based points ledger, redemption and reversal, tiers, expiry with an advance notice, rewards and single-use vouchers, and twelve signed webhooks; a replica consumer that declares no exchange |
| `backend/services/workflow/` | Workflow app: a Tenant's workflow definitions and their immutable published versions, the durable engine that runs them, instances, human tasks, connections, triggers and signed events; a replica consumer that acts as the Tenant's own system |
| `backend/samples/tenant/` | Sample Tenant System, built and deployed independently; it calls Billing over REST with a Platform-issued suite token |
| `frontend/` | Suite Console: one pnpm + Turborepo monorepo with two shells (Platform, Tenant) and every UI as a Module Federation remote (`remote-platform`, `remote-billing`), built on shared packages (`shell-core`, `shell-sdk`, `auth`, `ui`, `api-client`); see [docs/console.md](docs/console.md) |
| `backend/libs/core/` | The technical core every service builds against: kernel, Spring implementations, messaging (`core-messaging`), the Platform replica (`core-replica`), snapshot sync (`core-platform-sync`), suite policy (`core-suite`), signed webhook mechanics (`core-webhook`), the shared Feign client (`core-feign`) and the shared architecture rule set (`core-arch`); the only code they share |
| `contracts/` | OpenAPI, event schemas, the messaging contract, permission catalogs and the token claim list, shared between services as documents, never as code |
| `ops/` | Local runtime: PostgreSQL init, RabbitMQ users, the Keycloak realm import, the Caddy route table, the load-test entry and the smoke scripts |

`backend/` separates the three kinds of Gradle build: `services/` are deployable products, `libs/` is the shared
technical core, and `samples/` is a reference Tenant System that proves the integration. Only `services/` and
`samples/` are deployed, and each service is its own Gradle build. Inside a service the modules are grouped by role, and each Gradle project
path follows its directory:

| Directory | Contents |
|---|---|
| `contexts/` | The bounded contexts of that service, under the same group name in all three (Billing: eight; Platform: `tenancy`, `access`, `integration`; the sample tenant: eight) |
| `libs/` | `shared-kernel` (the service's value objects; `shared-core` in the sample tenant) and `jooq-schema` |
| `apps/` | `app-boot`, which wires the contexts and holds the migrations |
| `testing/` | `arch-fixtures`, the deliberately violating classes the architecture rules are tested against |

Read [docs/architecture.md](docs/architecture.md#module-boundaries) for what each context owns, and
[docs/overview-and-scope.md](docs/overview-and-scope.md) for the suite model.

## Build

Each service builds on its own and needs JDK 25 and a Docker daemon for Testcontainers:

```bash
cd backend/libs/core && ./gradlew build
cd backend/services/platform && ./gradlew build
cd backend/services/billing && ./gradlew build
cd backend/services/mailer && ./gradlew build
cd backend/services/notification && ./gradlew build
cd backend/services/mailroom && ./gradlew build
cd backend/services/survey && ./gradlew build
cd backend/services/attribution && ./gradlew build
cd backend/services/booking && ./gradlew build
cd backend/services/customer && ./gradlew build
cd backend/services/loyalty && ./gradlew build
cd backend/services/workflow && ./gradlew build
cd backend/samples/tenant && ./gradlew build
```

`./gradlew check` in every service also runs the isolation gate: a Gradle task that fails when the service
declares a dependency on another service's code, and ArchUnit rules that fail when a class names another
service's package.

## Run the whole suite

`docker compose up` starts the suite behind one reverse proxy, each service in its own database with its own role
and broker user:

```bash
cp .env.example .env                 # then replace every placeholder value
docker compose up -d --build --wait   # PostgreSQL, RabbitMQ, Keycloak, Platform, Billing (api + worker), tenant, Caddy
docker compose ps                     # every service healthy
```

`make help` lists the shortcuts: `make up` creates `.env` with generated secrets when it is missing, and in an existing
one only appends the variables `.env.example` gained since, keeping every value already there (see
`ops/env/generate-env.sh`), then starts the stack, `make console` builds the console and publishes its shells and
remotes into it (then activate the new remote versions in Platform Admin), and `make dev` runs the hot-reload dev servers.

The proxy listens on `:8090` and routes on the Host header alone: `platform.localhost` reaches Platform at its
root, and `/platform/*` on any host reaches Platform with the prefix stripped, so `<tenant-code>.localhost/platform/*`
is a tenant-scoped Platform call. Everything else goes to Billing, which reads `<tenant-code>.<base-domain>` from
the same Host to pick the tenant. `*.localhost` resolves to the local machine in current browsers.

Platform migrates on start; Billing writes its schema once through the `billing-migrate` step and its api and worker
runtimes connect as `billing_app` with Flyway off. Every service has a health check and starts only after the
services it depends on are healthy.

Infrastructure only, with the apps on `bootRun` (no image build):

```bash
make infra                      # PostgreSQL, RabbitMQ, Keycloak and Mailpit in Docker
make run SERVICE=platform       # one terminal per service; Platform first, then billing, notification, ...
make run SERVICE=billing
```

`make run` takes each service's settings from its block in `docker-compose.yml`, so `.env` stays the only place for
secrets, and points them at the host ports compose publishes. It runs the service's migrate step first when it has
one, then the api and worker runtimes in one process. Each service has its own host port, listed in
`ops/dev/run-service.sh`, so the whole suite can run side by side. The `caddy` service on `:8090` routes to containers,
so it does not reach services started this way. `make gateway` starts `gateway-host` instead: the same
`ops/caddy/Caddyfile`, with every upstream pointing at that host port, also on `:8090`. Run it instead of `make up`'s
Caddy, never beside it; it is what `make dev` (the console's dev servers) and the tenant hostnames
(`<tenant>.localhost:8090`) go through.

The same suite also deploys to AWS ECS Fargate with managed RDS and Amazon MQ, in parallel with compose rather than
instead of it. The infrastructure lives in [`ops/terraform/`](ops/terraform/README.md); `docker-compose.yml` is
untouched by that path.

## Verify the suite

Two scripts prove the Done-means end states against the running stack; CI runs both:

```bash
ops/smoke/isolation.sh    # no service can reach another's database or publish to another's exchange
ops/smoke/suite-smoke.sh  # create a tenant, watch the replica, call Billing with a suite token, deliver a webhook,
                          # revoke a permission and see Billing refuse
```

`isolation.sh` reads `.env`; `suite-smoke.sh` reads the Keycloak and bootstrap administrator settings from the
environment (see the script's header). After an intended API change, regenerate a contract with
`./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`.
