# Commands between services

A **command** is a request one app sends to the app that owns the work: "do this", not "this happened". The
transport is the same as an event's — one canonical envelope over RabbitMQ, defined in
[messaging/README.md](../messaging/README.md) — and the only difference is ownership of the type.

## Who owns a command

**The consumer owns the command; a producer implements it.** An event type belongs to the service that emits it,
so Billing and Platform own their `contracts/events/<app>/` directories. A command type belongs to the service
that *does* the work, because that service decides what it needs to perform it. A producer therefore adds no
schema and changes no contract: it reads this directory and sends what is described here.

| Rule | Why |
|---|---|
| A command's schema lives under `contracts/commands/`, one file per command, named `<type>.schema.json` | The type is the routing key, so the file name is the type and no lookup table is needed |
| The schema describes `data` only | The envelope is fixed and shared; only the payload belongs to the command |
| A producer never edits a command schema | Otherwise two apps can disagree about a request the owner already implements |
| A message with no schema is never published | The consumer validates `data` against it before doing anything |

A command schema must **not** live under `contracts/events/<app>/`: `SchemaEventTypeCatalog` reads
`classpath*:events/*.schema.json` in Billing and fails the build when a file has no constant in `SuiteEventTypes`.
A command placed there would break Billing's tests.

## Envelope

Identical to an event's: `id`, `type`, `aggregate`, `tenantId`, `occurredAt`, `schemaVersion`, `data`. The type is
the routing key. `id` is the producer's UUID v7 and is the deduplication key, so a producer that retries a request
must reuse the same `id` (a *new* `id` for the same logical request is the consumer's problem, which is why every
command carries its own idempotency key as well).

## The trust boundary — read this before writing a producer

**A command's `tenantId` is asserted by the publisher, not authenticated by the consumer.**
`AmqpInboundDispatcher` binds the transaction scope straight from `event.tenantId()`
(`backend/libs/core/core-messaging/.../inbound/AmqpInboundDispatcher.java`), and broker users are per service, never per
tenant. So anything holding `write` on a command exchange can name any tenant in the suite.

The controls are:

- one broker user per service, scoped to its own exchanges and queues (`ops/rabbitmq/declare-users.sh`, proved by
  `ops/smoke/isolation.sh`);
- the per-service allowlist of AWS accounts, for a command that can act in an external account;
- rejection of the reserved platform tenant on a path that has no legitimate platform-scope use.

A stronger design — per-tenant routing keys, or an HMAC over the envelope — is deliberately not built, because it
needs a second producer to justify it. A producer author should treat a grant of `write` on a command exchange as
a grant to act for any tenant.

## Commands

| Type | Exchange | Queue | Consumer | Schema |
|---|---|---|---|---|
| `email.send` | `mailer.commands` | `mailer.commands` | `mailer` | [email.send.schema.json](email.send.schema.json) |

`email.send` carries an already-rendered email. The consumer delivers it; it never chooses recipients, renders a
template or interprets a business event, because those decisions belong to the app whose business they are.
