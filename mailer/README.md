# mailer

An outbound email gateway. It owns exactly one thing: taking an already-rendered email request off a queue and
delivering it reliably, with a record of what happened. It reports the terminal outcome back as one event,
`email-delivery.changed` on `mailer.events`, through a transactional outbox. It does not choose recipients, render
templates or interpret business events — those decisions belong to the app whose business they are. See
[docs/mailer.md](../docs/mailer.md) and the command contract in
[contracts/commands/email.send.schema.json](../contracts/commands/email.send.schema.json).

```bash
./gradlew build                                   # compile, ArchUnit, Spring Modulith, the jOOQ drift test, the isolation gate
./gradlew :apps:app-boot:bootRun                  # no profile: migrates, connects nothing
SPRING_PROFILES_ACTIVE=worker ./gradlew :apps:app-boot:bootRun   # the listener and every job
```

## Shape

One context, `email`, split into the suite's four layers. `Domain` holds the delivery aggregate, its status machine and
the retry policy; `application` holds the ports and the four use cases; `adapter` holds the transports, the jOOQ
repositories and the credential resolver; `api` is empty on purpose, because no other context binds to this one.

The seam is `EmailSender`: SMTP for local development, SESv2 in a deployment. Everything that is not a send — the
ledger, the inbox, backoff, the lease — lives above it, so both adapters inherit it unchanged. They are two adapters
rather than one configuration because assumed-role credentials cannot authenticate over SMTP at all.

Two runtimes: `migrate` writes the schema once and exits, `worker` runs the listener and every job. There is no `api`
runtime; the only HTTP surface is the actuator probe.

## Why the ledger looks like this

One row per `(tenant, requestId, recipient)`, not one per request: a single bad address cannot hide the successful
deliveries beside it, and a rejection of one recipient cannot roll back the others. `attempts` moves in the **claim**,
not in the outcome, so a process that dies after the provider accepted the message cannot re-send forever without
exhausting its budget. The claim selects `PENDING` *and* `IN_PROGRESS`, so a row a lost worker left behind comes back
when its lease runs out.

A repeated `requestId` is a defined answer, not a silent no-op: live rows are left alone, a `DEAD` row is resurrected.

Accepting a command registers its tenant in `mailer_tenants`, because the delivery sweep visits only the tenants listed
there; before that, a command for an ordinary tenant was accepted and then sat `PENDING` for ever.

A terminal outcome appends `email-delivery.changed` to the outbox in the same transaction as the ledger outcome, and the
worker's relay publishes it. The publish is guarded by the outcome's compare-and-swap, so a worker whose row another
worker took over publishes nothing.

## Local pipeline

`docker compose up` starts Mailpit on 1025 (SMTP) and 8025 (UI and HTTP API), `mailer-migrate` once, and
`mailer-worker`. The worker depends on Mailpit with `service_started`, never `service_healthy`: the ledger's backoff is
what should handle a dead SMTP server, and a hard dependency would turn a transient outage into "the worker never
starts". `management.health.mail` is disabled for the same reason.

## Build convention

Each module's `api`, `domain`, `application` and `adapter` layers are separate source sets, so Spring cannot leak into the
plain-Java inner layers. `./gradlew checkNoForeignServiceDependency` fails if any module declares a dependency on
Billing, Platform or the sample tenant, on either the project axis or the dependency **group** axis — the second one is
what catches a sibling build, which arrives as an external coordinate. `ArchitectureTest`, its fixture meta-test and
`ModulithVerificationTest` re-check the same boundaries on the classpath.

## Persistence

`libs/jooq-schema` holds the jOOQ tables generated from the Flyway migrations and committed, so a normal compile needs no
database. `JooqSchemaTest` migrates a throwaway PostgreSQL, regenerates and fails when the committed code is out of date:

```bash
./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true
```

Integration tests need Docker (one PostgreSQL 18 container per test JVM) and connect as `mailer_app`, the unprivileged
role, because a superuser bypasses row-level security and would leave the isolation guarantee unverified.
