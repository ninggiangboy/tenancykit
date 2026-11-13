# Core Billing

Documentation lives in [docs/](docs/README.md). Code lives in:

| Directory | Contents |
|---|---|
| `platform/` | Platform service (Gradle multi-module, Spring Boot): tenants, users, centralized RBAC, app entitlement, tenant systems and the suite's tokens |
| `billing/` | Core Billing app (Gradle multi-module, Spring Boot): the eight billing contexts, replicating the Platform data it enforces |
| `tenant/` | Sample Tenant System, built and deployed independently; it calls Billing over REST with a Platform-issued suite token |
| `web/` | Web apps (Suite Console, Customer Portal, Sample Tenant UI) |
| `core/` | The technical core every service builds against: kernel, Spring implementations, messaging (`core-messaging`) and the Platform replica (`core-replica`); the only code they share |
| `contracts/` | OpenAPI, event schemas, permission catalogs and the token claim list, shared between services as documents, never as code |
| `ops/` | Local runtime: PostgreSQL init, RabbitMQ users, the Caddy route table and the smoke scripts |

Each service is its own Gradle build. Inside a service the modules are grouped by role, and each Gradle project
path follows its directory:

| Directory | Contents |
|---|---|
| `contexts/` | The bounded contexts of that service (Billing: eight; Platform: `tenancy`, `access`, `integration`) |
| `libs/` | `shared-kernel` (the service's value objects) and `jooq-schema` |
| `apps/` | `app-boot`, which wires the contexts and holds the migrations |
| `testing/` | `arch-fixtures`, the deliberately violating classes the architecture rules are tested against |

Read [docs/architecture.md](docs/architecture.md#module-boundaries) for what each context owns, and
[docs/overview-and-scope.md](docs/overview-and-scope.md) for the suite model.

## Build

Each service builds on its own and needs JDK 25 and a Docker daemon for Testcontainers:

```bash
cd core && ./gradlew build
cd platform && ./gradlew build
cd billing && ./gradlew build
cd tenant && ./gradlew build
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

The proxy listens on `:8090` and routes on the Host header alone: `platform.localhost` reaches Platform at its
root, and `/platform/*` on any host reaches Platform with the prefix stripped, so `<tenant-code>.localhost/platform/*`
is a tenant-scoped Platform call. Everything else goes to Billing, which reads `<tenant-code>.<base-domain>` from
the same Host to pick the tenant. `*.localhost` resolves to the local machine in current browsers.

Platform migrates on start; Billing writes its schema once through the `billing-migrate` step and its api and worker
runtimes connect as `billing_app` with Flyway off. Every service has a health check and starts only after the
services it depends on are healthy.

Infrastructure only, with the apps on `bootRun`:

```bash
docker compose up -d postgres rabbitmq keycloak
set -a; source .env; set +a
cd billing && ./gradlew :apps:app-boot:bootRun
```

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
