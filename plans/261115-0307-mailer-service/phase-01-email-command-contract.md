---
phase: 1
title: "Email Command Contract"
status: pending
priority: P1
effort: "S"
dependencies: []
---

# Phase 1: Email Command Contract

## Goal

Define `email.send` as a documented command with a JSON schema, so the consumer has something authoritative
to validate against and a future producer has something to implement.

## Key Insights

- `contracts/messaging/README.md` already states that a message is either an event or a command and that the
  transport is identical. It also says only a type with a schema is published or subscribed. Commands
  therefore need their own schema directory.
- `contracts/commands/` does not exist. It was created and then deleted during the 2026-11-12 pivot, so this
  phase re-creates it deliberately and should say so in the README.
- Command schemas must not live under `contracts/events/<app>/`: `SchemaEventTypeCatalog`
  (`billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/infrastructure/messaging/SchemaEventTypeCatalog.java`)
  reads `classpath*:events/*.schema.json` and fails the build when a file has no constant in
  `SuiteEventTypes`. A command placed there would break Billing's tests.
- The envelope is fixed and already documented: `id`, `type`, `aggregate`, `tenantId`, `occurredAt`,
  `schemaVersion`, `data`. Only `data` is described by this schema.

## Files to Create / Modify

- Create: `contracts/commands/README.md` — one file per command, `<type>.schema.json`, `data` only.
- Create: `contracts/commands/email.send.schema.json`
- Modify: `contracts/messaging/README.md` — add the command section and a topology row for `mailer.commands`.
- Modify: `contracts/README.md` — add `commands/` to the ownership table.

## Architecture

Payload under `data`, with `additionalProperties: false` and hard limits so an oversized or misspelled
document is rejected at the boundary rather than in the worker:

| Field | Type | Rule |
|---|---|---|
| `requestId` | string | 1–200 chars; producer-chosen idempotency key |
| `to` | string[] | 1–50 items, `format: email` |
| `cc` | string[] | optional, 0–50 |
| `fromKey` | string | 1–64 chars, `^[a-z0-9][a-z0-9._-]*$`; a configuration key, never an address |
| `fromName` | string | optional, 1–128 chars; display name chosen by the producer |
| `replyTo` | string | optional, `format: email` |
| `subject` | string | 1–998 chars |
| `html` | string | optional, max 262144 chars |
| `text` | string | optional, max 262144 chars |

At least one of `html` or `text` must be present; the schema expresses this with `anyOf`.

`fromKey` is a key rather than an address on purpose: the address must be verified in the account that
sends, so it belongs to mailer's configuration, while the display name is the producer's branding. This
split lets a future campaign app and the notification service share the service without either being able to
spoof an arbitrary sender.

`fromKey` alone is not enough. `fromName`, `replyTo` and `to` are also producer-controlled and reach a
mail client directly, so each needs a constraint:

| Field | Added rule | Why |
|---|---|---|
| `fromName` | `pattern` denying `"`, `<`, `>`, `,`, CR and LF | otherwise it breaks out of the display-name position |
| `subject` | `pattern` denying CR and LF | header injection |
| `replyTo` | must be validated against the resolved credential's allowed reply-to domain | an arbitrary reply-to turns a DKIM-valid suite email into business-email compromise |
| `to` | 1–50 addresses, each `format: email`, no CR/LF | the schema is the only bulk limit |

Header-injection characters are rejected in the schema **and** again in a `Recipient`/`Subject` value object
at decode time, because the schema protects the wire and the value object protects every later caller.

## Implementation Steps

1. Create `contracts/commands/README.md`: a command schema describes `data` only, the type is the routing
   key, the consumer owns the command, and a producer implements the contract rather than defining it.
2. Write `contracts/commands/email.send.schema.json` following the style of
   `contracts/events/billing/invoice.created.schema.json`: `$schema`, `$id`, `title`, `description`,
   `type: object`, `required`, `additionalProperties: false`, then properties with limits.
3. Express the `html`/`text` requirement with `anyOf: [{required: ["html"]}, {required: ["text"]}]`.
4. Add the command paragraph to `contracts/messaging/README.md` under "Envelope".
5. Add the topology row to **`docs/suite-messaging.md:8-16`**, which is the document that owns the exchange
   table (`docs/README.md:18` says so), as well as to `contracts/messaging/README.md`. Adding it to only one
   of the two leaves operators with a stale map.
6. Add `commands/` to the table in `contracts/README.md`.
7. Record the trust boundary in `contracts/commands/README.md`: a command's `tenantId` is asserted by the
   publisher, not authenticated by mailer. Anything holding `write` on `mailer.commands` can name any tenant.
   Phase 10's broker scoping and the per-service broker user are the only controls; write this down where a
   producer author will read it.

## Todo

- [ ] `contracts/commands/README.md` with the command-ownership and trust-boundary rules
- [ ] `contracts/commands/email.send.schema.json` with limits, `additionalProperties: false`, header-injection patterns, `anyOf` html/text
- [ ] `contracts/messaging/README.md` command section
- [ ] `docs/suite-messaging.md:8-16` topology row for `mailer.commands` + DLQ
- [ ] `contracts/README.md` gains `commands/`
- [ ] `jq` verification passes

## Verification

```bash
jq -e '.properties.data.additionalProperties == false' contracts/commands/email.send.schema.json
python3 -c "import json; d=json.load(open('contracts/commands/email.send.schema.json')); print(sorted(d['properties']['data']['properties']))"
grep -n "mailer.commands" contracts/messaging/README.md contracts/commands/README.md
```

`jq` must print `true`, the field list must match the table above, and the topology section must name both
the queue and its dead-letter queue.

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| Schema lands under `contracts/events/` | Billing build fails in `SchemaEventTypeCatalog` | Move it to `contracts/commands/`; that is the only fix |
| Producer sends an address in `fromKey` | Consumer rejects with a schema error | Working as designed; the message names a key, the address is resolved server-side |
| Body limits added later, in code only | Oversized bodies reach the queue and fail in the worker | Keep the limits in the schema; the consumer validates before writing |
| Header injection via `subject`/`fromName` | A mail client shows a different recipient or a forged display name | Reject CR, LF, `"`, `<`, `>`, `,` in the schema and in the value objects |
| Arbitrary `replyTo` | Replies to a trusted suite email land at an attacker address | Validate `replyTo` in the resolver against a per-credential allowlist, not in the adapter |

## Security Considerations

- Recipient addresses are personal data. They are stored in the ledger; the message body is stored under a
  short TTL and purged (phase 4).
- The `fromKey` pattern stops a producer smuggling an address or a path into the field.
- No credentials, tokens or signed URLs in the schema or the docs.

## Next Steps

Phase 2 copies the `tenant/` skeleton; it does not depend on the schema, so the two can proceed in either
order.
