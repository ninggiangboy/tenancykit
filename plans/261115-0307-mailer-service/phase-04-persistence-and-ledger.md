---
phase: 4
title: "Persistence and Delivery Ledger"
status: completed
priority: P1
effort: "M"
dependencies: [2]
---

# Phase 4: Persistence and Delivery Ledger

## Goal

Create the mailer schema — tenants, deliveries, attempts, bodies, credentials, inbox — with row-level
security on every tenant-owned table, and commit the generated jOOQ schema so a drift test guards it.

## Key Insights

- Copy the RLS template from `billing/apps/app-boot/src/main/resources/db/migration/V1__tenancy.sql:7-39`:
  the app role owns nothing and cannot bypass RLS, `app_tenant_id()` reads the transaction GUC, and
  `enable_tenant_rls(tbl)` enables + forces RLS with a `tenant_isolation` policy on `using` and `with check`.
- **Every tenant-owned table needs the call, including credentials and bodies.** The Stripe precedent
  (`billing/.../V9__payments.sql:43`) ends with `call enable_tenant_rls('stripe_configurations')`. A table
  without it is globally readable by any tenant-scoped transaction.
- **`event_inbox` must have four columns, not three.** `core/core-messaging/src/main/resources/dev/ngb/core/messaging/schema-contract.md:43-50`
  specifies `tenant_id`, `consumer`, `event_id` **and `processed_at timestamptz not null default now()`,**
  and says every service using `JooqEventInbox` must create them in its own migration. `JooqEventInbox` only
  inserts three, so a missing `processed_at` is invisible until something needs to age rows out.
- `event_inbox` must have **no foreign key** to a tenants table;
  `billing/.../V25__inbox_without_tenant_fk.sql` records why. Mailer has no platform replica, so `tenant_id`
  is a bare uuid everywhere except the two tables below that intentionally reference the projection.
- **`completed_at` must be allowed on `DEAD`.** Billing's webhook table uses
  `check ((status in ('SUCCEEDED', 'DEAD')) = (completed_at is not null))`
  (`billing/.../V7__webhooks.sql:56`). A constraint of the form `status = 'SUCCEEDED' = …` rejects the `DEAD`
  write, which would make the terminal state unreachable and turn every permanent rejection into an infinite
  resend loop.
- **A scheduled job needs a tenant scope, and mailer has no replica to get one from.**
  `ScopedTenantContext.current()` throws `IllegalStateException("no request scope is bound…")`
  (`core/core-spring/.../tenancy/ScopedTenantContext.java:16-19`), and `SpringUnitOfWork.transactional` calls
  the hook before doing anything. Billing's jobs work only because `TenantSweep` wraps each one in
  `tenantContext.runAs(RequestScope.tenant(id), …)` over `ReplicaTenants.activeIds`
  (`billing/.../boot/jobs/TenantSweep.java:29-38`). Mailer needs its own tenant list, so this phase adds a
  `mailer_tenants` projection that ops maintains, and phase 7 sweeps it.
- The Stripe credential table (`billing/.../V9__payments.sql:16-43`) is the model for `mailer_credentials`:
  ciphertext columns, `check` constraints that make a half-configured row impossible, and a partial unique
  index for "at most one live row".
- jOOQ is generated from the migrations and committed; the drift test regenerates and compares.
- **Flyway placeholders come from Billing, not the tenant.** Billing's `V1__tenancy.sql:9-11` interpolates
  `${appDbPassword}`, supplied by `billing/apps/app-boot/src/main/resources/application.yml:38-39`. The
  tenant has no placeholders because it has no RLS. Copy the placeholder block or the migration aborts.

## Files to Create / Modify

- Create: `mailer/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`
  — app role, `app_tenant_id()`, `enable_tenant_rls()`, grants and default privileges
- Create: `.../V2__delivery.sql` — `mailer_tenants`, `email_deliveries`, `email_delivery_attempts`
- Create: `.../V3__bodies.sql` — `email_bodies`
- Create: `.../V4__credentials.sql` — `mailer_credentials`
- Create: `.../V5__inbox.sql` — `event_inbox` with `processed_at`, no foreign key
- Regenerate: `mailer/libs/jooq-schema/src/main/java/dev/ngb/mailer_jooq/**`
- Create: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/support/PostgresContainerConfig.java` — keep the
  tenant's container helper but **also** the app role, so tests can run as the unprivileged role
- Create: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/support/RepositoryPaths.java`
- Create: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/boot/persistence/JooqSchemaTest.java`
- Create: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/boot/persistence/RowLevelSecurityTest.java`
- Modify: `mailer/apps/app-boot/src/main/resources/application.yml` — datasource + flyway owner/app
  credentials **and** `spring.flyway.placeholders.appDbPassword`
- Modify: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/architecture/ArchitectureRules.java` — fill `tableOwner`

## Architecture

```
mailer_tenants                       -- the sweep's tenant list; ops-maintained, RLS off
  tenant_id uuid primary key, active boolean, created_at, updated_at

email_deliveries                     -- RLS
  id uuid pk, tenant_id uuid not null, request_id text not null, recipient text not null,
  from_key text not null, subject text not null,
  status text not null check (status in ('PENDING','IN_PROGRESS','SUCCEEDED','DEAD')),
  attempts int not null default 0 check (attempts >= 0),
  next_attempt_at timestamptz, claimed_until timestamptz,
  provider_message_id text, last_error text,
  created_at timestamptz not null, completed_at timestamptz,
  unique (tenant_id, request_id, recipient),
  check ((status in ('SUCCEEDED','DEAD')) = (completed_at is not null))

email_delivery_attempts              -- RLS
  tenant_id, delivery_id, attempt_no, outcome check (outcome in ('ACCEPTED','REJECTED','TRANSIENT')),
  error, attempted_at,  unique (tenant_id, delivery_id, attempt_no)

email_bodies                         -- RLS
  tenant_id, delivery_id pk (tenant_id, delivery_id), subject, html, text, created_at
  index on (created_at) for the purge

mailer_credentials                   -- RLS
  id, tenant_id, from_key, mode check (mode in ('SHARED','TENANT')), region,
  role_arn, external_id_ciphertext, aws_account_id,
  from_address, from_name, reply_to_allowlist text[],
  status check (status in ('ACTIVE','DISABLED')), version bigint, last_verified_at, created_at, updated_at
  check (mode = 'SHARED' or (role_arn is not null and external_id_ciphertext is not null and aws_account_id is not null))
  check (mode = 'TENANT' or (role_arn is null and external_id_ciphertext is null))
  unique (tenant_id, from_key, version)

event_inbox                          -- RLS
  tenant_id, consumer, event_id, processed_at timestamptz not null default now()
  primary key (tenant_id, consumer, event_id)
  index on (processed_at)
```

`mailer_tenants` is deliberately not RLS-protected: the sweep must read every tenant id before it can assume
any tenant's scope. It holds no personal data and no delivery content.

The body lives in its own table so the ledger row survives a purge. That is what makes a `DEAD` row
resendable for a short window while still bounding retained personal data.

## Implementation Steps

1. Write `V1__baseline.sql` from Billing's: create the app role if absent with `nobypassrls`, grant schema
   usage, `app_tenant_id()`, `enable_tenant_rls()`, default privileges for the owner role.
2. Write `V2__delivery.sql`: `mailer_tenants`, then the delivery and attempt tables, then
   `call enable_tenant_rls('email_deliveries')` and `call enable_tenant_rls('email_delivery_attempts')`.
   Index `(tenant_id, status, next_attempt_at)` — that is the claim predicate.
3. Write `V3__bodies.sql` with `email_bodies` and `call enable_tenant_rls('email_bodies')`.
4. Write `V4__credentials.sql` with both `check` constraints, `aws_account_id`,
   `reply_to_allowlist`, `unique (tenant_id, from_key, version)`, and
   `call enable_tenant_rls('mailer_credentials')`.
5. Write `V5__inbox.sql` with the four columns, no foreign key, `enable_tenant_rls('event_inbox')`, and an
   index on `processed_at`.
6. Set datasource and flyway properties **including the `appDbPassword` placeholder**, copying
   `billing/apps/app-boot/src/main/resources/application.yml:32-39`.
7. Generate jOOQ with `-Djooq.update=true` and commit the output.
8. Fill `Profile.tableOwner` for every generated table.
9. Copy the drift test from `tenant/apps/app-boot/src/test/java/dev/ngb/tenant/boot/persistence/JooqSchemaTest.java`.
10. Write `RowLevelSecurityTest`: connect as `mailer_app` with `app.tenant_id` unset, assert a read of
    `email_deliveries` and `mailer_credentials` returns zero rows; set tenant A's id, insert a row, then set
    tenant B's id and assert the row is invisible and an insert with A's id fails the `with check`.

## Todo

- [x] `V1__baseline.sql`: app role, `app_tenant_id()`, `enable_tenant_rls()`, grants
- [x] `V2__delivery.sql`: `mailer_tenants`, deliveries, attempts; RLS on the two tenant tables
- [x] `V3__bodies.sql` with RLS and a purge index
- [x] `V4__credentials.sql` with both `check` constraints, `aws_account_id`, `reply_to_allowlist`, `(tenant_id, from_key, version)` unique
- [x] `V5__inbox.sql` with `processed_at`, no foreign key, RLS
- [x] `application.yml` datasource + flyway owner/app **and the `appDbPassword` placeholder**
- [x] jOOQ regenerated and committed; `tableOwner` filled
- [x] `JooqSchemaTest` (drift) and `RowLevelSecurityTest` (as `mailer_app`) green
- [x] Three psql proofs for the `completed_at` predicate recorded

## Verification

```bash
cd mailer && ./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' --tests '*RowLevelSecurityTest'
cd mailer && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest'
cd mailer && ./gradlew build
```

Then prove the constraint shapes with the owner role:

```bash
# DEAD must be writable with completed_at
psql "$MAILER_OWNER_URL" -c "insert into email_deliveries (tenant_id, request_id, recipient, from_key, subject, status, attempts, created_at, completed_at)
  values (gen_random_uuid(),'r1','a@b.c','k','s','DEAD',1,now(),now());"        # must succeed
# DEAD without completed_at must fail
psql "$MAILER_OWNER_URL" -c "insert into email_deliveries (tenant_id, request_id, recipient, from_key, subject, status, attempts, created_at)
  values (gen_random_uuid(),'r2','a@b.c','k','s','DEAD',1,now());"              # must fail
# SUCCEEDED without completed_at must fail
psql "$MAILER_OWNER_URL" -c "insert into email_deliveries (tenant_id, request_id, recipient, from_key, subject, status, attempts, created_at)
  values (gen_random_uuid(),'r3','a@b.c','k','s','SUCCEEDED',1,now());"         # must fail
```

All three assertions belong in the PR; the first one is the one a naive constraint gets wrong.

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| `check` allows `completed_at` only for `SUCCEEDED` | A `DEAD` write fails with 23514 inside the outcome transaction, and the row re-sends forever | Use Billing's predicate including `DEAD`; prove all three cases with psql |
| `event_inbox` created with three columns | `JooqEventInbox` works, but nothing can ever purge the table | Add `processed_at` and its index now; adding it later is a migration on a large table |
| A foreign key on `tenant_id` | The first message for an unreplicated tenant fails to record | Drop it, per `V25__inbox_without_tenant_fk.sql` |
| RLS omitted on credentials or bodies | Cross-tenant read of sender identities and message content | `enable_tenant_rls` on every tenant-owned table; `RowLevelSecurityTest` asserts it |
| Tests run as the container superuser | RLS and the GUC hook are never exercised; production denies everything | One test must connect as `mailer_app` |
| Missing `${appDbPassword}` placeholder | Flyway aborts the whole migration set | Copy the placeholder block from Billing's `application.yml` |
| `tableOwner` left empty | `context-uses-only-own-tables` passes vacuously | Fill it for every generated table |

## Security Considerations

- RLS is the isolation guarantee, and it is only a guarantee if at least one test runs as the unprivileged
  role with the GUC unset. The tenant's test harness deliberately uses a superuser; copying it unchanged
  would leave the guarantee unverified.
- `external_id_ciphertext` is encrypted with `SecretCipher` (`dev.ngb.core.security.SecretCipher`,
  implementation `AesGcmSecretCipher` in `core-spring`) using the row id as associated data.
- Bodies contain personal data by definition; the TTL purge is a requirement and is owned by phase 7.

## Next Steps

Phase 5 wires the database into compose. Phase 7 owns the purge job and the tenant sweep that this schema
makes possible.
