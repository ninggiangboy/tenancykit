---
phase: 6
title: "crm custom fields, tags and consent"
status: done
priority: P2
effort: "M"
dependencies: [5]
---

# Phase 6: crm Custom Fields, Tags and Consent

## Goal

A Tenant defines its own typed customer fields and consent topics, tags customers, and records consent with a history that
can answer "what did this customer agree to, and when".

## Context

Typed, immutable-key definitions follow the survey decision (a type change is a new field, never an edit). Migration range
`V20`. This phase extends phase 5's customer representation with `fields` and `tags`; phase 5 must not reference them.

## Files to create

- `crm/apps/app-boot/src/main/resources/db/migration/V20__fields_tags_consent.sql`; regenerate the jOOQ schema
- `crm/contexts/customers/...` features `field`, `tag`, `consent` (command, query, port, service, persistence, controllers)
- Tests: `CustomFieldIntegrationTest`, `TagIntegrationTest`, `ConsentIntegrationTest`

## Tasks

1. **Field definitions** `field_definitions(tenant_id, key, label, type, choices, archived, created_at)`; key
   `^[a-z][a-z0-9_]{0,39}$`, immutable; `type` is `TEXT`, `NUMBER`, `DATE`, `BOOLEAN` or `CHOICE` (a `CHOICE` has 1-50
   values). `PATCH` changes label, choices (add only) and `archived`. A type change answers `409`. At most 50 active
   definitions per Tenant (a setting).
2. **Values.** `customers.fields jsonb` keyed by field key, validated on `PUT`, `PATCH` and batch against the active
   definitions: an unknown or archived key is `422`, a wrong type is `422`, `null` clears a value. A GIN index
   (`jsonb_path_ops`) on `(fields)` serves phase 8's filters.
3. **Tags.** `customer_tags(tenant_id, customer_id, tag)`; tags are lower-case `^[a-z0-9][a-z0-9 _:-]{0,49}$`, at most 50 per
   customer. `PUT` body `tags` replaces the set; `PATCH` takes `addTags` and `removeTags`; `GET /tags` lists distinct tags
   with a customer count, keyset paginated.
4. **Consent.** `consent_topics(tenant_id, key, label, archived)` (same key rules as fields) and an append-only
   `consent_events(id, tenant_id, customer_id, topic_key, status GRANTED|REVOKED, occurred_at, recorded_at, source,
   evidence_ref, consent_ref)`. `POST /customers/{ref}/consents` appends one; an optional `consentRef` makes the call
   replayable. The current status of a topic is the latest event by `occurred_at`, then id. `occurred_at` may not be more
   than 5 minutes in the future. `GET .../consents` returns the current status per topic and the history, newest first.
5. **Permissions.** `crm:field:manage` for definitions and consent topics, `crm:customer:manage` to write values, tags and
   consents, `crm:customer:read` to read.

## Verification

- `cd crm && ./gradlew build` green.
- Tests prove: a type change is refused; an archived field can be read but not written; a customer cannot hold 51 tags; a
  consent history is never rewritten (revoke after grant leaves both rows and the status `REVOKED`); two consents with the
  same `consentRef` produce one row; fields, tags and consent topics of tenant A are invisible to tenant B.

## Risks

- Unbounded JSON: cap the serialized size of `fields` (16 KiB, a setting) and reject larger bodies with `422`.
- Consent rows hold `source` and `evidence_ref` free text that may contain personal data: phase 9 clears them on erasure and
  its schema-driven test must list both columns.
