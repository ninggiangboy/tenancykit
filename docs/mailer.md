# Mailer

The suite's **delivery** service. It takes an email a producer has already rendered and hands it to a transport: Amazon
SES in a deployment, SMTP (Mailpit) locally. It is a separate deployable (`mailer/`, root package `dev.ngb.mailer`) with
**no business HTTP API and no load balancer**, because nothing calls it — it reacts to a command on the broker.

## What it is, and what it is not

| It is | It is not |
|---|---|
| A delivery service: one `email.send` command in, one ledger row and one email per recipient out | A notification service: it decides nothing about who is notified, or when |
| The owner of a delivery ledger, per tenant and per recipient | A template engine: recipients, subject and both bodies arrive in the command |
| A sender through the suite's own SES identity, or through a Tenant's own AWS role | A mailbox, or a bounce and complaint processor: SES's asynchronous events are not consumed |
| A consumer that publishes nothing | A producer: it declares no exchange of its own and has no outbox table |

Who is notified, which event notifies them, the templates and their translations stay outside the suite, so mailer is the
one exception to that non-goal; see [overview-and-scope.md](overview-and-scope.md).

## Runtimes

Two profiles, one image, deployed as one service and one one-shot task (`ops/terraform/mailer/`):

| Runtime | Profile | What runs |
|---|---|---|
| `mailer-migrate` | `migrate` | Flyway as `mailer_owner`, then exits. Run to completion before the worker moves |
| `mailer` | `worker` | The `mailer.commands` listener and every job: delivery, housekeeping, credential health |

The worker serves no business HTTP and declares no port mapping. It keeps an actuator probe on its server port so a stuck
task is distinguishable from a healthy one, and its readiness group includes `db`, so a task whose database went away
stops advertising itself as ready.

## Broker

| Item | Name |
|---|---|
| Command exchange | `mailer.commands` (topic) |
| Command queue | `mailer.commands`, bound to the exchange with routing key `email.send` |
| Dead-letter exchange | `suite.dlx` (topic, shared by the suite) |
| Dead-letter queue | `mailer.commands.dlq`, bound to `suite.dlx` with the queue's own name as its routing key |

The dead-letter routing key is set explicitly to the queue's name, because the broker otherwise keeps the original key
and the DLQ binding would never match.

The broker user `mailer` is scoped to `mailer.commands`, `mailer.commands.dlq` and `suite.dlx`, and can publish
nothing. `ops/rabbitmq/declare-users.sh` is where those permissions are written down; the `mq-users` task applies them to
a managed Amazon MQ broker.

A command is accepted in one database transaction and never sent from the listener: the handler writes the ledger rows
and returns, so a message the broker redelivers can never send an email twice. Two keys make that safe — the inbox, keyed
by the envelope's event id (consumer name `mailer.email-send`), and the ledger, keyed by
`(tenant, requestId, recipient)`.

A command that fails schema validation writes nothing at all and lands in `mailer.commands.dlq` after the dispatcher's
retries. A command naming the Platform tenant is refused the same way.

## The delivery status machine

```
                 claim                accepted
PENDING ─────────────────▶ IN_PROGRESS ──────────▶ SUCCEEDED   (terminal)
   ▲                           │
   │                           ├─ transient ─▶ PENDING (backoff, attempts + 1)
   │                           └─ rejected, or attempts exhausted ─▶ DEAD   (terminal)
   └── resurrect: a producer re-sends the same requestId
```

| Status | Means | Leaves it when |
|---|---|---|
| `PENDING` | Waiting for its next attempt, the first one or the one after a transient failure | A worker claims it |
| `IN_PROGRESS` | A worker holds it; `claimed_until` is its lease | The send returns, or the lease expires and it becomes claimable again |
| `SUCCEEDED` | The transport accepted the message. Under SES that means *accepted*, not delivered | Never: terminal |
| `DEAD` | The provider rejected the request, or the retry budget ran out | Only a producer re-sending the same `requestId` |

`DEAD` is reachable on the **first** attempt. A permanent rejection — SES answering `403 AccessDenied`,
`MessageRejected`, `MailFromDomainNotVerified`, `SendingPaused`, `AccountSuspended`, or any other 4xx — is translated as
*rejected*, not *transient*, so the row dies immediately instead of spending the whole retry budget and the send quota on
a request that cannot succeed. A revoked tenant role is exactly that case.

A transient failure — a 429, a 5xx, throttling, a transport timeout — returns the row to `PENDING` with exponential
backoff, and only the attempt budget makes it `DEAD`: ten attempts by default, backed off from 30 seconds to 6 hours with
jitter. `completed_at` is set for both terminal states and for no other.

Every attempt is appended to `email_delivery_attempts`, which the application role may only insert into: the history is
evidence, and the ledger's status is only its last line.

Two switches stop work without losing it: `mailer.delivery.enabled = false` keeps rows `PENDING` until it is switched
back on, and `mailer.jobs.enabled = false` stops the scheduled jobs. Neither drops a queued email.

Housekeeping purges message bodies after 7 days and inbox rows after 30 days. A body is personal data; the ledger row
outlives it.

## Dead-letter runbook

A message in `mailer.commands.dlq` is one no consumer could process. It is not a delivery: it never reached the ledger,
or it did and the transaction rolled back.

1. Read the payload and the consumer's stack trace in the log. The envelope carries the `type`, the `tenantId` and the
   event id; mailer logs the `requestId` and the recipient count, never the body.
2. Look at the delivered count for that tenant and request id:
   `select status, attempts, last_error from email_deliveries where tenant_id = '<tenant>' and request_id = '<requestId>'`.
   No rows means the command was rejected before it wrote anything.
3. Fix the cause — usually a producer sending a payload that does not match `contracts/`, or a message for the Platform
   tenant, which mailer refuses by design.
4. Move the message back with a one-shot shovel from `mailer.commands.dlq` to `mailer.commands` with routing key
   `email.send`, then delete the shovel so a later failure is not silently replayed. Republishing by hand is the same
   thing and is enough for a single message — the management API takes the envelope unchanged:
   ```bash
   # request body for POST /api/exchanges/%2F/mailer.commands/publish
   jq -n --rawfile payload message.json \
     '{properties: {}, routing_key: "email.send", payload: $payload, payload_encoding: "string"}' > publish.json

   curl --silent --show-error --fail-with-body \
     --user "admin:${ADMIN_PASSWORD}" --request POST \
     --header "Content-Type: application/json" --data @publish.json \
     "http://localhost:15672/api/exchanges/%2F/mailer.commands/publish"
   ```
5. If the message is unprocessable by design, record why and drop it. A command is a request, and a producer that still
   wants the email can send it again — see below.

## Resending a dead delivery

A `DEAD` row is not a tombstone. A producer re-sends the **same `requestId`** for the same recipient, as a new event
(the inbox sees it for the first time), and the ledger finds a matching row that is `DEAD` and resurrects it to
`PENDING`; the delivery job then sends it on its next round. The handler logs `resurrected a dead delivery`.

The full rule, because a retry may arrive at any moment:

| Ledger rows for that `(tenant, requestId)` and recipient | What the command does |
|---|---|
| None | Creates the delivery as `PENDING` |
| All live (`PENDING`, `IN_PROGRESS`) or `SUCCEEDED` | Nothing: the email is going out or already has, and a repeated request must never send a second copy |
| Any `DEAD` | Resurrects exactly those rows, leaves the live ones alone |

So the way to retry a dead delivery is to fix the cause (a bad address, a revoked role, an unverified identity) and have
the producer send the same request again — not to edit the row. Editing `email_deliveries` by hand would leave the
attempt history lying about what happened, and the row would still be dead the moment the cause is unfixed.

## Health

`management.health.mail` is **disabled on purpose** (`management.health.mail.enabled: false`). An SMTP or SES outage is
exactly what the delivery ledger's backoff is for: the worker's job is to wait it out, and a health contributor that
probed the mail server would report the worker `DOWN`, so a supervisor — or anything that replaces an unhealthy task —
would restart the process that is supposed to be waiting. Readiness covers the database, liveness covers the JVM, and
nothing else probes the transport.

The other failure mode has its own job: the credential health sweep re-probes tenant roles that have not been verified
recently, evicts a cached provider that stopped working, and reports a count. A revoked role also surfaces at send time
as `AccessDenied`, which kills those deliveries immediately rather than retrying them.

## Where the code lives

`mailer/contexts/email` owns the delivery domain and ledger, the SES and SMTP adapters and the credential resolver;
`mailer/apps/app-boot` owns the wiring, the jobs and the Flyway migrations. `core/core-messaging` owns the envelope, the
inbox and `SuiteTopology`, so mailer's listener is a wiring exercise rather than a reimplementation. Deployment is
`ops/terraform/mailer/`.

## Related

Tenant onboarding: [mailer-tenant-onboarding.md](mailer-tenant-onboarding.md). The suite's messaging topology and its
dead-letter rules: [suite-messaging.md](suite-messaging.md). Which profile runs what, and the rollout order:
[runtime-profiles.md](runtime-profiles.md).
