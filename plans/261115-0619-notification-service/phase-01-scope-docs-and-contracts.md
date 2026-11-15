---
title: "Phase 1: Scope, Docs and Contracts"
status: todo
---

# Phase 1: Scope, Docs and Contracts

## Context Links

- `docs/overview-and-scope.md:100-103` — the non-goals list that names a notification service; `:109` still says the
  first app beyond Billing is a non-goal, which this plan also makes false
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/catalog/ClasspathAppCatalog.java:23`
- `platform/apps/app-boot/build.gradle.kts:51-56` — where `contracts/permissions` is packaged
- `contracts/permissions/billing.yaml` — the catalog format to copy
- `plans/261112-0726-enterprise-suite-foundation/phase-10-email-and-templates.md` — the cancelled scope being replaced

## Overview

Priority P1. Nothing is built in this phase, and no code changes. It makes the repository honest before the service
exists: the documented scope currently forbids exactly what the next seven phases build, and the app code has no
permission catalog. Doing this first means every later phase builds against a spec that already agrees with it.

The decision reversal is deliberate and belongs in the documents, not only in a chat log: the operator needs to be able
to read why a non-goal became a deployable without reconstructing it from git history.

## Key Insights

- `ClasspathAppCatalog` treats **the file name** as the app code: `permissions/*.yaml` → the set of app codes. A new
  `notification.yaml` therefore registers `notification` in Platform with no Platform change; the entitlement row a
  Platform admin creates is the only thing left.
- The non-goal paragraph is one bullet, but it also carries the rationale that made it a non-goal ("who gets notified,
  which events notify them, the templates and the translations stay out of the suite"). Replacing it with "we do this
  now" loses the reasoning; the replacement must say what changed: recipients are Platform users, so no business data
  crosses a boundary.
- The "Three deployables" table states the count in its heading. Leaving it at three while four exist is the kind of
  contradiction that later reads as a documentation bug rather than a decision.
- `docs/suite-messaging.md` owns the topology table, and `contracts/messaging/README.md` owns the canonical one. Both
  list queues per consuming app; the new queue must appear in both or the two documents disagree.

## Requirements

Functional

- The scope documents state that the suite now includes a notification app, what it owns, what it still refuses, and
  the date and reason for the reversal.
- A permission catalog exists with exactly the codes Phase 7 will enforce.
- The messaging topology lists `notification.platform-events` and the `email.send` producer relationship.

Non-functional

- No build may go red. `platform`, `billing`, `mailer`, `tenant` and `core` builds are unchanged and still pass.
- No speculative codes: every code in the catalog is used by an endpoint in Phase 7.

## Architecture

The catalog's four codes. **Revised:** the two template codes are **platform** scope, because a template is the suite's
content and one row serves every tenant; the settings and dispatch codes stay tenant scope. None is grantable to a
Tenant System credential (these are human actions).

| Code | Scope | Purpose |
|---|---|---|
| `notification:template:read` | platform | Read the resolved template for a type and locale, including the shared override |
| `notification:template:manage` | platform | Create, update and delete the suite-wide template override |
| `notification:config:manage` | tenant | Enable or disable a notification type and set the tenant default locale |
| `notification:dispatch:read` | tenant | Read the email dispatch ledger for auditing what was and was not sent |

Own-notification endpoints (list, read, read-all, own preference) carry **no** permission code, matching how a user
reads their own data elsewhere in the suite. Being an authenticated principal is **not** the whole authorization
though: `@Authenticated` also admits a Tenant System credential, and a machine has no user id. Those endpoints therefore
need an explicit user-principal guard (Phase 7).

**Revised** scope rule: the original "every endpoint requires a tenant scope" is now per endpoint group. Templates are
platform-only; settings and the dispatch ledger are tenant-only; the inbox and the caller's own preference accept either
scope for a user principal, because a Platform administrator has an inbox too. One blanket rule would either close the
only door the suite needs to edit shared content or open a third one onto every tenant endpoint.

## Related Code Files

Create

- `contracts/permissions/notification.yaml` — the four codes above

Modify

- `docs/overview-and-scope.md` — non-goal bullet replaced; "Three deployables" becomes four with a `notification/` row
- `docs/architecture.md` — a notification section and its module-boundary row, mirroring Billing's
- `docs/suite-messaging.md` — topology: new queue, its binding, its dead-letter queue, and the producer side
- `contracts/messaging/README.md` — the same topology row in the canonical table

## Implementation Steps

1. Edit `docs/overview-and-scope.md`. Replace the notification bullet under Non-goals with a paragraph under the app
   description: `notification/` is the suite's app for **notifying Platform tenant users** about platform-side state
   changes; it owns templates, locale, preferences and the in-app store; it does not notify a Tenant's customers and
   reads no business data from another app; email delivery stays with `mailer/`. Record the date and that it supersedes
   phase 10 of `261112-0726-enterprise-suite-foundation`.
2. In the same file, change the deployables table heading and add the fourth row: owns notification types, templates,
   preferences and in-app notifications; reached at `<tenant-code>.<base-domain>` through the `/notification` prefix.
   Then fix the other bullet this makes false — "A first business app beyond Billing" — by saying the suite's first app
   beyond Billing is `notification/`, a platform-facing app rather than a business app, and that the extension trace is
   now proven rather than hypothesised.
3. Add the module-boundary row in `docs/architecture.md` (what it owns, what it may read, what it may publish),
   mirroring how Billing's row is written.
4. Add the queue to `docs/suite-messaging.md`: `notification.platform-events` bound to `platform.events` with the five
   state-transfer routing keys, `x-dead-letter-exchange: suite.dlx`, and `notification.platform-events.dlq`. State that
   the app publishes `email.send` to `mailer.commands` and declares no exchange of its own.
5. Add the identical row to `contracts/messaging/README.md` under Topology.
6. Write `contracts/permissions/notification.yaml` in the same shape as `billing.yaml`, with the four codes and
   `scopes: [tenant]`, `grantable_to_tenant_system: false`.
7. Verify the catalog reaches Platform's classpath: build Platform and inspect the jar for
   `permissions/notification.yaml`.

## Todo

- [x] Scope documents updated, reversal recorded with date and rationale
- [x] Architecture and messaging docs updated (both topology tables)
- [x] `contracts/permissions/notification.yaml` written
- [x] Platform build green and the catalog file visible in the packaged jar
- [x] Billing, mailer, tenant and core builds green

## Success Criteria

- `cd platform && ./gradlew :apps:app-boot:bootJar` succeeds and
  `unzip -l platform/apps/app-boot/build/libs/app-boot.jar | grep permissions/notification.yaml` prints the entry.
- `grep -n "notification" docs/overview-and-scope.md` shows the new positive scope and no longer shows the old
  non-goal sentence.
- `cd billing && ./gradlew build`, `cd mailer && ./gradlew build`, `cd tenant && ./gradlew build`,
  `cd core && ./gradlew build` all pass.
- `grep -c "^- code:" contracts/permissions/notification.yaml` is 4.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Documentation drifts from the eventual implementation (codes get renamed) | Phase 7 asserts the constants equal this catalog, so a rename fails the build |
| Adding a catalog file breaks a test that assumes one app | Verified: `PermissionCatalogTest` reads `billing.yaml` specifically; run the builds |
| A reader thinks the old phase 10 is still authoritative | Both this plan and the scope paragraph say it is superseded; phase 10 already carries a Cancelled banner |
| The reversal is invisible to a future reader | The dated sentence in the scope document plus this plan's Key Decisions table; no second copy is kept |

## Security Considerations

- The four codes are tenant-scoped and refused to Tenant System credentials: a machine credential must not edit a
  tenant's templates.
- No endpoint uses a code that is absent here, and no code here is unused: the catalog is the single source of truth
  for what a role may hold.
- The scope document must not describe recipients as "any address": it must say Platform tenant users, which is the
  boundary that keeps personal data out of the notification database.

## Next Steps

Phase 2 creates the build. Nothing in Phase 2 may add a permission code or a routing key that this phase did not
record; if it must, this phase is reopened first.
