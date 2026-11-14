# Runtime profiles and deployment

Each service jar runs in one shape. Billing's one jar runs in three: `api`, `worker` and `migrate`. `dev` and `test`
start Billing with no profile, which activates `api` and `worker` together — the all-in-one behaviour from before the
split; production starts one of the profiles below.

Platform and the sample tenant have no such split; each runs as one process and migrates on start. Under `docker
compose`, Platform and the tenant boot normally, while Billing writes its schema once through `billing-migrate` and
its `api` and `worker` runtimes connect as `billing_app` with Flyway off.

| Profile | Serves | Builds | Migrates |
|---|---|---|---|
| `api` (N instances) | HTTP and Stripe webhooks | no job bean, no scheduler | no |
| `worker` (M instances) | actuator probes only | every job, no business controller | no |
| `migrate` (one step) | nothing | no jobs, no bootstrap | Flyway as the table owner, then exits |
| default (no profile) | HTTP and jobs | `api` and `worker` together | yes |

Each edge declares its beans positively: a controller carries `@Profile("api")`, a job carries `@Profile("worker")`,
and architecture tests fail when either annotation goes missing. `spring.profiles.default` in `application.yml`
supplies both profiles to a run that sets none.

The two profile files describe a runtime that runs alone, so each guards itself: `application-api.yml` carries
`spring.config.activate.on-profile: api & !worker` and `application-worker.yml` carries `worker & !api`. Without the
guard the dev/test default would load both files, and the worker would win every shared key — Flyway off, a 5s
`lock_timeout` and eight-way tenant parallelism — instead of the base `application.yml` that the default relies on.

Deploy in the order `migrate`, `api`, `worker`. The migrate step is the only writer of schema; api and worker connect
as the unprivileged application role with Flyway switched off, so a rolling start never races a migration.

## The suite's processes

`docker compose up` runs one reverse proxy, one PostgreSQL server (one database per service), one RabbitMQ, Keycloak,
Platform, Billing's api and worker, and the sample tenant. The reverse proxy routes on the Host header only: it adds
no logic, retries nothing and rewrites no body. See [suite-messaging.md](suite-messaging.md) for the broker and
[platform-service.md](platform-service.md) for Platform's shape.

| Process | Runs | Reaches |
|---|---|---|
| `caddy` | The route table in `ops/caddy/Caddyfile` | Platform and Billing |
| `platform` | Tenancy, access, integration, the token endpoint, the JWKS, the snapshot API, the outbox relay | Its database, the broker, Keycloak |
| `billing` (`api`) | HTTP, the portal and Stripe webhooks; the replica rebuild and the `platform.events` listener | Its database, the broker, Platform |
| `billing-worker` (`worker`) | Every Billing job: outbox relay, webhook delivery, billing cycle, renewals and so on | Its database, the broker |
| `billing-migrate` (`migrate`) | Flyway as the table owner, then exits | Its database |
| `tenant` | Its own HTTP surface, webhook verification, the Platform token provider, the execution worker | Its database, Platform, Billing |

## What each profile owns

- **Jobs.** `worker` owns outbox relay, webhook delivery, subscription renewal, billing cycle close, reservation
  expiry, ledger verification, Stripe retry and Stripe reconciliation. `api` owns none of them. A test asserts both
  halves.
- **The replica listener.** The `platform.events` listener lives in Billing's `worker` (a `@Profile("worker")`
  container), started after the rebuild by `ReplicaBootstrap`, so the `api` runtime stays free of broker consumption
  while the worker keeps the replica current. A run with no profile (dev and test) activates both.
- **Jobs that must not run twice.** Ledger verification and Stripe reconciliation take a time-bounded lease in
  `job_leases`; a per-day `job_runs` record makes a second ledger verification the same day a no-op. Ledger
  verification runs on a short fixed delay from 03:00 UTC rather than once at 03:00: a worker that dies mid-round
  hands the remaining tenants to the next tick of another worker once the lease expires, a tenant whose
  verification failed gives its run record back so a later tick tries it again, and a claim left unfinished by a
  worker that died is taken over once it is older than the lease TTL. The platform bootstrap runs under
  the same lease, so two instances starting together do not both seed. See
  [concurrency-and-locking.md](concurrency-and-locking.md).
- **Skip-locked jobs.** Outbox, webhook delivery, subscription renewal, billing cycle close, reservation expiry and
  Stripe event retry claim their rows with `for update skip locked`, so they stay multi-worker safe and are
  deliberately not made singleton. The Stripe event retry also takes a claim deadline of its own
  (`stripe_events.claimed_until`), because it reads from Stripe before it opens the transaction that locks the row:
  a claimed event is invisible to another worker until that deadline passes, and a worker that dies mid-attempt
  hands it back instead of losing it.
- **Rate limiting.** Billing runs no limiter of its own: it holds no API keys, and the credentials a Tenant System
  exchanges for a suite token are Platform's. The `POST /oauth/token` limits live in Platform's `rate_limit_buckets`:
  a claimed client id spends its own bucket before its secret is compared, and a request that does not authenticate
  also spends the caller's address and one shared anonymous bucket, which has its own wider allowance
  (`platform.security.token-rate-limit.anonymous-burst`), so inventing client ids cannot multiply a caller's attempts.
  A Platform job removes idle buckets in bounded `skip locked` batches, repeating while a batch comes back full.
- **Threading.** `api` enables virtual threads, so a wait for Stripe or Keycloak does not occupy a request thread,
  and pairs that with a 3s Hikari `connection-timeout`: the pool stays at 20, so a request that cannot get a
  connection is refused with a 503 and `Retry-After: 1` instead of queueing for 30s. `worker` deliberately stays on
  platform threads, because enabling virtual threads there would replace the nine-thread scheduler with
  `SimpleAsyncTaskScheduler` and drop `spring.task.scheduling.pool.size`, which the worker relies on. The pool has one
  thread per scheduled method, so a long round cannot hold up the pollers behind it. Jobs bound their own concurrency
  with `billing.jobs.tenant-parallelism`, not with the scheduler's thread count.

## Settings per profile

| Setting | `api` | `worker` | `migrate` |
|---|---|---|---|
| Flyway | off | off | on, owner account |
| Hikari pool | `BILLING_API_DB_POOL_SIZE` (20) | `BILLING_WORKER_DB_POOL_SIZE` (10) | 2 |
| Hikari `connection-timeout` | 3s | default | default |
| Virtual threads | enabled | off (platform threads) | off |
| Tenant parallelism | not applicable | `BILLING_WORKER_TENANT_PARALLELISM` (8), below the pool | not applicable |
| Scheduler pool | none | `BILLING_WORKER_SCHEDULER_POOL_SIZE` (9, one per scheduled method) | none |
| `lock_timeout` | 30s (waiting for a wallet is intended) | 5s (skip the tick, try again) | 3s, bounded retry |
| `idle_in_transaction_session_timeout` | 60s | 60s | default |
| Shutdown | graceful, 30s phase timeout | graceful, scheduler awaits up to 30s | not applicable |
| Probes | `/actuator/health/liveness`, `/actuator/health/readiness` | same | none |
| Flyway `init-sql` | not set | not set | `SET lock_timeout = '3s'` |

The exact values live in `application-api.yml`, `application-worker.yml` and `application-migrate.yml`. They are
starting points measured by the deadlock stress test, not constants to defend: raise the api timeout before raising
the worker's.

## ECS Fargate

`ops/terraform/` deploys the same profiles to AWS and keeps `docker compose` as the local path; neither replaces the
other. The deployment has four long-running workloads and three one-shot tasks:

| ECS workload | Profile | Notes |
|---|---|---|
| `keycloak` | Keycloak `start` | Self-hosted, RDS-backed, realm export baked into the image. |
| `platform` | none | 2 replicas; migrates on start; Service Connect name `platform`. |
| `billing-api` | `api` | 2 replicas; the ALB's default target; Service Connect name `billing`. |
| `billing-worker` | `worker` | 2 replicas; no ALB target; no virtual threads. |
| `billing-migrate` | `migrate` | One-shot task run to exit 0 before api and worker roll. |
| `rds-init` | n/a | One-shot task that creates the databases and owner/app roles RDS does not provision. |
| `mq-users` | n/a | One-shot task that applies the broker's per-user exchange/queue permissions. |

ECS has no `depends_on: service_completed_successfully`, so ordering is the pipeline's job: `rds-init` and `mq-users`
run once, `billing-migrate` runs on every deploy, and only then are the services pointed at the new task definitions.
Billing's api and worker services pin their task definition in Terraform, which is what makes that ordering possible;
`terraform apply` alone never moves them.

RDS and Amazon MQ replace the postgres and rabbitmq containers, with the same one-owner-one-app role split per
database. Service Connect replaces the compose network alias for service-to-service calls. It does not solve Host
header routing: Platform still resolves a tenant from `request.getServerName()`, so `PLATFORM_INTERNAL_HOSTNAMES`
must list `platform.<namespace>` or Billing's internal call 404s.

The issuer rule is unchanged and is the easiest thing to break: the issuer is the public address a user signs in at,
while JWKS and Platform's replica calls use the internal Service Connect address. See
[`ops/terraform/README.md`](../ops/terraform/README.md) for the full address table, ALB rule priorities and the
deployment steps.

## Related

Lock order and coordination rules: [concurrency-and-locking.md](concurrency-and-locking.md). Reliability requirements:
[security-and-reliability.md](security-and-reliability.md).
