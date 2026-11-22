---
phase: 2
title: "Caller model and label derivation"
status: pending
priority: P1
effort: 3d
dependencies: [1]
---

# Phase 2: Caller model and label derivation

## Goal
One shared definition of the seven callers, a `@Callers` annotation that makes every route state who may call it, and
one shared producer of `x-allowed-callers` that is checked against the permission catalog, replacing the copies in
Billing and Notification and the ad-hoc `x-public` flags.

## Files to Create / Modify
- Create: `backend/libs/core/core-kernel/src/main/java/dev/ngb/core/security/Caller.java` (enum of the seven, with the
  kebab-case wire value)
- Create: `backend/libs/core/core-kernel/src/main/java/dev/ngb/core/security/Callers.java`: the annotation that
  declares who may call a route, on a controller class (a default) or on a method (which wins), for example
  `@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})`; `@Callers(Caller.PLATFORM_USER)` on `AdminUserController`
- Modify: `backend/libs/core/core-kernel/src/main/java/dev/ngb/core/security/PublicEndpoint.java` (a `caller`
  attribute, default `PUBLIC`; `PROVIDER` for signed inbound webhooks), so a public route needs no second annotation;
  `@RequiresPortalPermission` already implies `customer-session`
- Create: `backend/libs/core/core-openapi/src/main/java/dev/ngb/core/openapi/CallerLabelCustomizer.java` and a small
  `CallerCatalog` port (`scopes(code)`, `grantableToTenantSystem(code)`)
- Modify: `backend/services/apps/billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/WebConfiguration.java`
  (delete `allowedCallers()` at line 172, use the shared customizer; `portal-session` becomes `customer-session`)
- Modify: `backend/services/infra/notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/web/WebConfiguration.java`
- Modify (wire the shared customizer): the web configuration of Platform, Mailroom, Survey, Attribution and Workflow;
  remove whatever emits `x-public` (located in phase 1)
- Modify: each generated `contracts/openapi/<app>.yaml` (regenerated)

## Tasks & Steps
1. Add `Caller` and `Callers`; extend `PublicEndpoint`.
2. Define the one rule for where a route's callers come from, in `CallerLabelCustomizer`. Every handler method must
   resolve to exactly one source, looked up in this order: `@Callers` on the method, `@Callers` on the class,
   `@PublicEndpoint(caller)`, `@RequiresPortalPermission` (→ `customer-session`). A method with none fails an
   architecture test in `core-arch`. The path is never read.
3. Add the catalog consistency check. For a route with `@RequiresPermission(code)` the declared callers must equal
   what the catalog says: `tenant-user` iff scopes contain `tenant`, `platform-user` iff they contain `platform`,
   `tenant-system` iff `grantable_to_tenant_system`, `internal` iff scopes are empty. Disagreement fails the build, in
   both directions, so the annotation (what a reader sees) and the catalog (what Platform validates credentials and
   roles against) can never drift. Routes with no permission code (`@Authenticated`, public, internal, self-service)
   take their callers from the annotation alone.
4. Make the labels visible in the rendered API docs: the same customizer appends one generated line to each
   operation's description, for example "Callers: tenant-user, tenant-system. Requires `billing:wallet:read`.",
   built from the label and the catalog entry. Scalar does not render `x-` extensions, so the description is the only
   place a reader sees it. Remove the hand-written "Requires `...`" sentences from `@Operation` descriptions that now
   duplicate it (search for them with the descriptions conf and the controllers).
5. Write the table-driven unit test for every rule in `core-openapi`, including the generated description line and
   the catalog consistency check (a mismatch in each direction fails).
6. Provide a `CallerCatalog` implementation per app on top of the shared catalog reader chosen in phase 1.
7. Replace Billing's and Notification's copies; wire the five other generated services.
8. Annotate every existing controller. Prefer `@Callers` on the class when all its methods share callers (most
   do, so this is a few hundred lines rather than one per route), and on the method only for exceptions. Run a
   one-off script that reads each method's catalog entry and proposes the annotation, then review the diff by hand;
   the consistency check from step 3 then confirms it. Controllers of Booking, Customer and Loyalty are annotated
   too even though their contract is hand-written.
9. Regenerate each generated OpenAPI file and review that only `x-allowed-callers` lines and the generated
   description line changed, plus the removal of `x-public`, of duplicated hand-written permission sentences, and the
   rename `portal-session` to `customer-session`.

## Verification
- `./gradlew test` in `backend/libs/core` (the new unit tests pass).
- In each wired service run its `*OpenApiContractTest` with `-Dopenapi.update=true`, then without; `git diff
  contracts/openapi` shows only label changes.

## Risks
- A method resolves to no source: the `core-arch` rule fails it; annotate it rather than special-casing the
  customizer.
- Effort grows with the number of controllers; the class-level default and the proposal script keep it mechanical.
