---
phase: 2
title: "Suite contracts"
status: pending
priority: P1
effort: "1d"
dependencies: [1]
---

# Phase 2: Suite contracts

## Goal

Write every cross-system contract before any service implements it: the message envelope and topology, Platform's replicated events, the `notification.requested` command, the per-app permission catalogs, and the suite token claims.

## Key insights

- `contracts/events/*.schema.json` today holds only Billing's public events, and `SchemaEventTypeCatalog` reads that folder. Namespacing by producer makes the Hub's rule simple: it forwards only types that have a schema under `contracts/events/billing/`.
- Replicated Platform data uses **state-transfer events**: one `*.changed` event per aggregate that carries the full current state and a `version`. A consumer upserts with a version guard and never has to apply deltas in order.
- The AMQP message body is exactly the public webhook body, so the Hub forwards and signs the producer's bytes without re-serializing them.
- Permission codes become namespaced by app: `billing:invoice:read`, `platform:tenant:create`, `notification:webhook:manage`.

## Files

Move:
- `contracts/events/*.schema.json` → `contracts/events/billing/` (19 files, `git mv`).

Create:
- `contracts/events/platform/tenant.changed.schema.json`: `id`, `code`, `name`, `status`, `created_at`, `version`. Platform also emits the reserved platform pseudo-tenant row (`00000000-0000-7000-8000-000000000001`), which Billing's platform-scope jobs need.
- `contracts/events/platform/tenant-domain.changed.schema.json`: `id`, `tenant_id` (nullable for `PLATFORM`), `hostname`, `kind`, `created_at`, `version`.
- `contracts/events/platform/tenant-system.changed.schema.json`: `id`, `tenant_id`, `name`, `status`, `created_at`, `version`.
- `contracts/events/platform/user-access.changed.schema.json`: `user_id`, `tenant_id`, `scope`, `keycloak_subject`, `email`, `display_name`, `status`, `permissions` (array of codes, all apps), `version`.
- `contracts/events/platform/entitlement.changed.schema.json`: `tenant_id`, `app_code`, `enabled`, `version`.
- `contracts/commands/notification.requested.schema.json`: `request_id`, `template_key`, `locale`, `channels` (`email`, `in_app`), `recipients` (`{type: "email", address, name}` or `{type: "user", user_id}`), `variables` (object), `link` (optional).
- `contracts/messaging/README.md`: transport, envelope and topology (see Architecture).
- `contracts/permissions/platform.yaml`, `billing.yaml`, `notification.yaml`: each entry has `code`, `description`, `scopes` (`platform` and/or `tenant`), `grantable_to_tenant_system`.
- `contracts/tokens/README.md`: suite token claims for service clients and tenant systems.

Modify:
- `contracts/README.md`: list the new folders.
- `contracts/events/README.md`: the folder split, and that delivery is AMQP between services and signed HTTP to tenant systems.
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/infrastructure/messaging/SchemaEventTypeCatalog.java` and its configuration: read `contracts/events/billing/`.
- Any test that loads schemas by path.

## Architecture

Envelope (AMQP body, JSON, UTF-8):

```json
{ "id": "<uuid v7>", "type": "invoice.created", "tenant_id": "<uuid>", "occurred_at": "2026-11-12T07:00:00Z",
  "schema_version": 1, "data": { } }
```

AMQP properties: `message_id` = `id`, `type` = `type`, `content_type` = `application/json`, header `tenant-id`, delivery mode persistent.

Topology:

| Exchange (topic, durable) | Producer | Routing key | Queues (one per consuming app) |
|---|---|---|---|
| `platform.events` | Platform | event type, e.g. `tenant.changed` | `billing.platform-events`, `notification.platform-events` |
| `billing.events` | Billing | event type | `notification.billing-events` |
| `notification.requests` | any app | `notification.requested` | `notification.requests` |

Each queue has `x-dead-letter-exchange: suite.dlx` and a matching `<queue>.dlq` bound by queue name. Consumers declare their queues and bindings; producers declare their exchange; both declarations are idempotent.

Permission mapping from `billing/contexts/platform/src/api/java/dev/ngb/billing/platform/api/actor/Permission.java`:

| Today | Catalog | New code |
|---|---|---|
| `tenant:*`, `tenant-domain:manage`, `user:*`, `role:*`, `tenant-system:*`, `credential:manage` | platform | `platform:<same>` |
| `webhook:read`, `webhook:manage` | notification | `notification:webhook:read`, `notification:webhook:manage` |
| every other code | billing | `billing:<same>` |
| new | platform | `platform:entitlement:manage`, `platform:replica:read` (service clients only) |
| new | notification | `notification:template:read`, `notification:template:manage`, `notification:delivery:read` |

Keep the current `platform`/`tenant`/`tenantSystem` flags as `scopes` and `grantable_to_tenant_system`.

Token claims (`contracts/tokens/README.md`): `iss` (Platform issuer URL), `sub` (`tenant-system/<id>` or `service/<client>`), `aud` (one app code), `exp` (10 minutes), `iat`, `jti`, `scope` (space-separated codes of that audience only), and for tenant systems also `tenant_id`, `tenant_code`, `tenant_system_id`.

## Implementation steps

1. `git mv` the Billing schemas; update `SchemaEventTypeCatalog`'s path and its tests; Billing build green.
2. Write the Platform event schemas and the command schema (JSON Schema draft 2020-12, like the existing ones).
3. Write the permission catalogs from the mapping table; add a Billing unit test that every `Permission` enum code appears in `billing.yaml` or `platform.yaml` or `notification.yaml` under the mapped name (it is deleted again in phase 8 when the enum goes).
4. Write the messaging and token READMEs.

## Todo

- [x] Billing schemas moved; Billing build green
- [x] Five Platform event schemas
- [x] `notification.requested` schema
- [x] Three permission catalogs with the mapping test
- [x] Messaging topology and token claims documented

## Verification

- `cd billing && ./gradlew build` green.
- Every new schema validates as JSON Schema: `npx -y ajv-cli@5 compile --spec=draft2020 -s 'contracts/events/platform/*.json' -s 'contracts/commands/*.json'`.
- The mapping test fails if a code is removed from a catalog.

## Risks

- Moving the schemas breaks a hard-coded path. Mitigation: grep for `contracts/events` across `billing/`, `tenant/` and `.github/` before moving.
- `email` in `user-access.changed` is personal data crossing services. It is needed for admin emails; only Platform, Billing and the Hub consume it, over the internal broker.
