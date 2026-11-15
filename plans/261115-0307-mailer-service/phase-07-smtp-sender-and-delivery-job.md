---
phase: 7
title: "SMTP Sender and Delivery Job"
status: completed
priority: P1
effort: "L"
dependencies: [6]
---

# Phase 7: SMTP Sender and Delivery Job

## Goal

Close the loop end-to-end: a tenant sweep drives a delivery job that claims due rows, sends through an SMTP
adapter to Mailpit, and records the attempt and the resulting status. This is where the plan's main success
criterion becomes true.

## Key Insights

- **A `@Scheduled` job has no tenant scope and will fail on every tick.**
  `SpringUnitOfWork.transactional` asks `hook.currentScope()` before doing anything
  (`core/core-spring/.../runtime/SpringUnitOfWork.java:32-33`), and `ScopedTenantContext.current()` throws
  `IllegalStateException("no request scope is bound; resolve one before touching the database")`
  (`.../tenancy/ScopedTenantContext.java:16-19`). Billing's jobs are safe only because `TenantSweep` wraps
  each call in `tenantContext.runAs(RequestScope.tenant(id), …)` over `ReplicaTenants.activeIds`
  (`billing/.../boot/jobs/TenantSweep.java:29-38`). Mailer has no replica, so it sweeps `mailer_tenants`
  (phase 4). Write the job as a sweep from the start; a job that calls the use case directly passes its unit
  test and delivers nothing in production.
- **The claim and the send cannot share a transaction**, so the claim must be committed first, the send made
  outside it, and the outcome written after — which is why the lease exists. `BrokerRelay`
  (`core/core-messaging/src/main/java/dev/ngb/core/messaging/broker/BrokerRelay.java:105-131,196-230`) is the
  in-repo spec for this exact shape: lease a batch with `for update skip locked`, commit, call out, record
  with attempts/backoff and a `DEAD` branch. `JooqWebhookDeliveryRepository.claimDue`
  (`billing/.../JooqWebhookDeliveryRepository.java:84-101`) is the query to model.
- **Increment `attempts` in the claim, not in the outcome.** If the process dies after the provider accepted
  the message but before the outcome commit, an outcome-side increment is lost and the row re-sends forever
  without ever exhausting its budget. Billing increments in the claim `UPDATE` and uses
  `attempts = claimedAttempts` as a compare-and-swap on the outcome write
  (`JooqWebhookDeliveryRepository.java:90-96,153-154`).
- **The claim must select `PENDING` *and* `IN_PROGRESS`.** A row stranded `IN_PROGRESS` by a crash is only
  recoverable by re-claiming it once the lease expires. Selecting only `PENDING` strands it permanently and
  the ledger reports "in progress" forever.
- **Bound the send by a timeout, and make the lease exceed it.** JavaMail's
  `mail.smtp.connectiontimeout` and `mail.smtp.timeout` default to *infinite*; one blackholed socket blocks
  the whole batch, the lease expires mid-send, and a second worker claims the same rows. Billing documents
  the invariant explicitly in `billing/apps/app-boot/src/main/resources/application.yml:69-77` ("worst case
  is this wait plus connect-timeout plus read-timeout, which must stay inside deadline"). Copy the property
  and validate lease > worst-case deadline in `DeliveryPolicy`.
- Spring Boot 4.1 still uses `JavaMailSender` (`org.springframework.mail.javamail`) over Jakarta Mail; the
  auto-configuration moved to the `spring-boot-mail` module but `spring.mail.*` is unchanged.
- Against Mailpit, leave `username`/`password` unset. `mail.smtp.auth=true` with no credentials is a hard
  failure; do not set `starttls.required`. There is **no** official Testcontainers Mailpit module
  (Testcontainers 2.0 renamed module artifacts; the core `GenericContainer` package is unchanged), so use
  `GenericContainer` with `Wait.forHttp("/api/v1/info").forPort(8025)`.
- Mailpit exposes `GET /api/v1/messages` (`total`), `GET /api/v1/message/latest`, `DELETE /api/v1/messages`.
  There is no server-side wait; poll.

## Files to Create / Modify

- Create: `mailer/contexts/email/src/adapter/.../email/smtp/SmtpEmailSender.java`
- Create: `.../email/persistence/JooqDeliveryClaimRepository.java` — claim (with attempts increment), outcome
  write with CAS, purge queries
- Create: `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/jobs/TenantSweep.java` — copy of Billing's
  shape over `mailer_tenants`, virtual-thread bounded by `mailer.jobs.tenant-parallelism`
- Create: `.../boot/jobs/DeliverEmailsJob.java` — `@Profile("worker")`, `@ConditionalOnProperty`, sweeps tenants
- Create: `.../boot/jobs/HousekeepingJob.java` — purges `email_bodies` by `created_at` and `event_inbox` by
  `processed_at`, bounded `delete … where ctid in (select ctid … limit n)`, skip-locked
- Create: `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/config/EmailConfiguration.java` — sender
  (`@ConditionalOnProperty(name="mailer.sender", havingValue="smtp")`), policy, jobs
- Create test: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/email/SmtpDeliveryIntegrationTest.java`
- Create test: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/email/HousekeepingJobTest.java`
- Modify: `mailer/apps/app-boot/src/main/resources/application-worker.yml` — `mailer.delivery.*`
  (`poll-delay`, `batch-size`, `max-attempts`, `lease`, `claim-deadline`), `mailer.jobs.*`,
  `mailer.housekeeping.*`, `mail.smtp.connectiontimeout/timeout/writetimeout`,
  `management.health.mail.enabled: false`
- Modify: `mailer/apps/app-boot/build.gradle.kts` — `spring-boot-starter-mail`

## Architecture

```
@Scheduled(fixedDelayString = "${mailer.delivery.poll-delay:PT2S}")
DeliverEmailsJob
  → TenantSweep.forEachActiveTenant(tenantId ->
      tenantContext.runAs(RequestScope.tenant(tenantId), () ->
        DeliverEmailsUseCase.deliverDue(tenantId)))
      → TX1 claim: select due rows where status in ('PENDING','IN_PROGRESS')
                     and next_attempt_at <= now() and (claimed_until is null or claimed_until < now())
                   for update of d skip locked limit batch
                   update … set status='IN_PROGRESS', attempts = attempts + 1,
                              claimed_until = now() + lease          -- attempts move here
                   commit; return claimedAttempts per row
      → outside any transaction: send each via EmailSender
          Accepted  → TX2 update … set status='SUCCEEDED', provider_message_id=…, completed_at=now()
                            where id=? and attempts=? and status='IN_PROGRESS'
          Rejected  → TX2 status='DEAD', last_error=…, completed_at=now()   (attempts already counted)
          Transient → TX2 if attempts >= maxAttempts → 'DEAD' + completed_at
                          else 'PENDING', next_attempt_at = now() + backoff(attempts)
          every branch also inserts email_delivery_attempts
      → assert the CAS affected exactly 1 row; 0 means another worker owns it, log and skip
```

TX2 wraps one delivery, not the batch: a single rejected recipient must not roll back the outcomes — and
the attempt rows — of the other deliveries in the same claim.

## Implementation Steps

1. Write `SmtpEmailSender`: `MimeMessage` from `OutgoingEmail`; `From` from the resolved address;
   `setPersonal(fromName)`; `Reply-To`; `multipart/alternative` when both parts exist. Map `MailException` to
   `Transient` and a `MessagingException` caused by an invalid address to `Rejected`.
2. Write the claim query exactly as above, returning `(id, attempts)` pairs after incrementing.
3. Write the outcome writes with the CAS predicate `id = ? and attempts = ? and status = 'IN_PROGRESS'`; return
   the affected row count and assert it.
4. Write `DeliverEmailsUseCase` with the three-way mapping and one transaction per delivery for the outcome.
5. Write `TenantSweep` and `DeliverEmailsJob`; the job must do nothing when `mailer.delivery.enabled` is false.
6. Write `HousekeepingJob` with two bounded deletes and their own switches.
7. Resolve `fromKey` in this phase through a small `SenderAddressLookup` reading configuration; phase 8
   replaces its source with the credential resolver **without** a third implementation.
8. Add the SMTP timeout properties and validate in `DeliveryPolicy` that `lease` exceeds
   connect+read+write timeouts; throw at construction otherwise.
9. Write the integration tests: one command → one Mailpit message with the right subject/recipient/body; the
   same request id again → still one message; Mailpit stopped → attempts increase and `DEAD` at the budget;
   Mailpit restarted → a new request succeeds; the job invoked with nothing bound → deliveries still happen
   (this is the sweep test that a use-case-only test misses); housekeeping deletes a backdated row.

## Todo

- [x] `SmtpEmailSender` with `multipart/alternative` and the `Transient`/`Rejected` mapping
- [x] Claim query: `status in ('PENDING','IN_PROGRESS')`, `skip locked`, **`attempts = attempts + 1` in the claim**
- [x] Outcome writes with CAS on `(id, attempts, status='IN_PROGRESS')`, one transaction per delivery
- [x] `TenantSweep` over `mailer_tenants`
- [x] `DeliverEmailsJob` + `HousekeepingJob` behind their switches
- [x] SMTP connect/read/write timeouts and the `lease > worst-case deadline` check
- [x] `SenderAddressLookup` stub (phase 8 replaces its source, not the abstraction)
- [x] Tests: happy path, replay, Mailpit down → `DEAD`, restart → success, job-with-no-scope, housekeeping purge

## Verification

```bash
cd mailer && ./gradlew :apps:app-boot:test --tests '*SmtpDeliveryIntegrationTest' --tests '*HousekeepingJobTest'
cd mailer && ./gradlew build
```

Manual check against compose, including the crash-window behaviour:

```bash
curl -s localhost:8025/api/v1/messages | jq '.total'
curl -s localhost:8025/api/v1/message/latest | jq '{Subject, To, Text}'
docker compose stop mailpit
# publish another command; watch attempts climb and status reach DEAD
psql "$MAILER_OWNER_URL" -c "select status, attempts, last_error from email_deliveries order by created_at desc limit 5;"
docker compose start mailpit
```

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| Job without a tenant scope | Scheduler logs "no request scope is bound" every poll and nothing sends | Sweep `mailer_tenants` and bind per tenant |
| `attempts` incremented in the outcome | A crash loop re-sends forever and never reaches `DEAD` | Increment in the claim, CAS on the outcome |
| Claim selects only `PENDING` | A crashed batch's rows are permanently invisible | Select `PENDING` and `IN_PROGRESS`, bounded by `claimed_until` |
| Infinite SMTP timeouts | One stuck socket stalls the batch; duplicate sends after the lease expires | Set connect/read/write timeouts and keep the lease longer |
| Outcome written per batch | One rejected recipient rolls back every other outcome in the claim | One transaction per delivery |
| Missing housekeeping job | Bodies and inbox rows grow without bound, contradicting the plan's retention claim | Ship the job with the schema, not later |

## Security Considerations

- The body is personal data; never log it. Log delivery id, recipient count, outcome and error code.
- The `From` address is always the resolved server-side value, never the producer's `fromKey` string.
- `management.health.mail.enabled: false` keeps an SMTP outage from killing the process that is supposed to
  back off through it.

## Next Steps

Phase 8 swaps the SMTP adapter for SES in production and replaces `SenderAddressLookup`'s configuration
source with the credential resolver.
