# Baseline and spikes

Plan: `plans/261122-1543-caller-surfaces/plan.md`. Branch: `refactor/group-services-by-kind`, from `ba50c97c`.
Phase: 1. This report records what the design treated as belief before any production code changed.

## 1. Catalog loading

Every app packages `contracts/permissions/*.yaml` on its classpath as `permissions/*.yaml`. Only Platform and Billing
read a catalog at runtime; the others only assert against it in a test.

| App | Gradle resource copy | Runtime reader | Test that reads it |
|---|---|---|---|
| platform | `backend/services/platform/apps/app-boot/build.gradle.kts:58-63` (all 10) | `YamlPermissionCatalog` (`contexts/access/.../catalog/yaml/`), strict: duplicate codes/unknown scopes fail startup; `CatalogTenantSystemScopes` bridges `grantable()` | `dev.ngb.platform.access.PermissionCatalogTest` (real reader) |
| billing | `backend/services/apps/billing/apps/app-boot/build.gradle.kts:73-77` (all 10) | `BillingPermissionCatalog` (`boot/catalog/`) — `find`, `codes`, `record Entry(code, scopes, grantableToTenantSystem)` | `dev.ngb.billing.contract.PermissionCatalogTest` (repo file) |
| notification | none — copies only `contracts/commands` (`build.gradle.kts:62-67`) | none; labels are hardcoded by controller class in `WebConfiguration` | repo file |
| mailroom | `.../mailroom/.../build.gradle.kts:71-75` (all 10) | none | repo file |
| survey | `.../survey/.../build.gradle.kts:69-73` (all 10) | none | repo file |
| attribution | `.../attribution/.../build.gradle.kts:71-75` (all 10) | none | repo file |
| booking | `.../booking/.../build.gradle.kts:67-71` (all 10) | none | repo file |
| workflow | `.../workflow/.../build.gradle.kts:68-72` (all 10) | none | repo file |
| customer | `.../customer/.../build.gradle.kts:66-76` (all 10) | none | classpath copy (`getResourceAsStream("/permissions/customer.yaml")`) |
| loyalty | `.../loyalty/.../build.gradle.kts:67-73` — **only `loyalty.yaml`** | none | repo file |

Decisions carried into phase 2:

- The one shared reader lives in **`core-openapi`** (`dev.ngb.core.openapi.catalog`), not `core-suite`. Only contract
  and label generation needs catalog data; `core-suite` is request-time enforcement and must not parse YAML.
  `core-openapi` needs `tools.jackson.dataformat:jackson-dataformat-yaml` on its classpath.
- Platform keeps its own strict `YamlPermissionCatalog` (it is an access-context port and validates at startup). Its
  web configuration may use the shared reader for labels in phase 7.
- Phase 2 must make **notification** package the catalogs and **loyalty** package all of them, so the shared reader
  sees a complete set in every app.
- Billing's `BillingPermissionCatalog` and the copies of the derivation in Billing/Notification are replaced by the
  shared customizer.

## 2. Second-prefix spike (Survey)

A throwaway test, `SurveyIntegrationTest` + `@AutoConfigureMockMvc(addFilters = false)`, was run twice and then
deleted. It registered a second `RequestMappingInfo` for every handler method whose path started with `/api/v1`,
rewritten to `/system/v1`, and inspected both routing and springdoc.

Mechanism (this is the phase 5 shape):

```java
RequestMappingInfo mirrored = original.mutate()
        .paths(mirroredPath)                    // one path per caller, not a union
        .build();
requestMappingHandlerMapping.registerMapping(mirrored, handlerMethod.getBean(), handlerMethod.getMethod());
```

Results:

- `RequestMappingHandlerMapping.getHandler("GET /system/v1/surveys")` returned the **same** `HandlerMethod` as
  `GET /api/v1/surveys` (`SurveyController.list`). Both paths route to one method.
- `GET /v3/api-docs.yaml` listed both paths: 70 paths, 87 operations (the run mirrored every `/api/v1` method,
  grantable or not). Document size 358,112 bytes, against 208,229 bytes / 38 paths / 47 operations committed today.
- **springdoc makes operationIds unique automatically** (`get` on one path, `get_1` on the other); no duplicates. But
  the assignment is not the one we want: in both runs the **mirrored `/system/v1` path kept the plain id and the
  canonical `/api/v1` path was renumbered `_1`**. That would churn every existing client id.
- Ordering was stable across two runs of the same context, but it is springdoc's internal ordering; phase 5 must pin
  ids deterministically (a `GlobalOpenApiCustomizer` that assigns the canonical path the base id and the mirrored path
  a caller-derived suffix) and assert it, rather than rely on `_1`.
- `registerMapping` is public and works after startup; the real mechanism must register once (idempotently) and must
  not register a union of paths, because a union mapping plus the original mapping makes the original path ambiguous.
- `@Autowired RequestMappingHandlerMapping` is ambiguous (`requestMappingHandlerMapping`,
  `controllerEndpointHandlerMapping`); qualify by bean name.

Fallback in `plan.md` (build-time generated `@RequestMapping` arrays and a catalog check) is not needed: the
runtime mapping mechanism is proven.

## 3. Self-service data

- **Notification inbox and preferences are strictly self-service.** The user id comes from `CurrentActor`, never a
  parameter: `ListNotificationsQuery:38-40`, `MarkNotificationReadUseCase:37,49`,
  `GetPreferenceUseCase:29-30`, `SavePreferenceUseCase:41`. Every repository condition includes
  `USER_ID.eq(userId)` (`JooqNotificationReadRepository:48`, `JooqNotificationReadStateRepository:24-45`,
  `JooqNotificationPreferenceRepository:24-26`), and RLS backs it. A foreign id answers `404`.
- **Platform `GET /api/v1/me`** returns the caller's own record and permission codes from `currentActor.get().id()`
  (`MeController:39-40`). Self-service.
- **`GET /api/v1/console/remotes`** is *scope-filtered*, not per-caller private: `ConsoleRemoteQueries.feed(Actor)`
  picks the audience and entitlement from `actor.scope()`/`actor.tenantId()`. The plan's move to
  `/api/v1/me/console-remotes` is still correct (it is the caller's own console view), but it is not "only my rows".
- **`UserController` and `RoleController` both derive reach from the actor**, not the Host: identical
  `tenantScope()` (`UserController:120-123`, `RoleController:118-121`), applied as a WHERE condition
  (`JooqUserReadRepository:57-59`, `JooqRoleReadRepository:51-53`). `null` means a platform-scope admin.
- **The only endpoints with no access annotation** are Booking's public gate:
  `backend/services/apps/booking/apps/app-boot/src/main/java/dev/ngb/booking/boot/gate/PublicController.java:53`
  (`@RequestMapping("/p")`, ten methods). Booking registers `PermissionInterceptor` for `/api/v1/**` only, so the
  fail-closed rule never sees them; the `PublicGateFilter` plus per-row ownership checks are the guard. This is not a
  mixed path, but phase 2 must annotate them (`@PublicEndpoint(caller = PUBLIC)`) and phase 2's architecture rule
  must fail them today. Booking's `PermissionCatalogTest` also lacks the "every endpoint declares an access rule"
  case the other apps have.
- No other endpoint in the 10 services lacks all three annotations. Billing has no `@Authenticated` methods at all,
  so none is a tenant-system operation by accident.

## 4. How apps reach Platform

- **Compose: direct, not through Caddy.** Each app's `*_PLATFORM_BASE_URL` is `http://platform.localhost:8080`
  (`docker-compose.yml:26,75,98,123,158,190,217,253,284`), resolved by the `platform` service network alias
  `platform.localhost` (`:491-498`). The replica snapshot client uses the same base URL
  (`ReplicaConfiguration:42-47,99-118`) and requests `/internal/replica/{stream}` directly. The replica listener is
  AMQP, not HTTP.
- **ECS: direct.** `ops/terraform/locals.tf:8,22` — `internal_fqdn = "platform.<namespace>"`,
  `platform_internal_url = "http://<internal_fqdn>:8080"` — passed as `PLATFORM_INTERNAL_HOSTNAMES` and every app's
  replica base URL (`platform/task-def.tf:17-19`, each app's `task-defs.tf`).
- **The ALB has no `/internal` rule.** `/internal/*` matches nothing and falls to the default Billing target;
  `/platform/internal/*` reaches Platform unstripped, which does not map to `SnapshotController:64`.
  `ops/terraform/alb/alb.tf:36-37` lists the complete rule set.
- **Gap recorded:** `PLATFORM_INTERNAL_HOSTNAMES` is set by Terraform but nothing under `backend/` reads it
  (`ops/terraform/README.md:115,328`; `docs/runtime-profiles.md:151-154`). It is not this plan's job to implement it,
  but phase 7/8 should not assume it works.
- Consequence for phase 8: blocking `*/internal/*` at the edge **cannot break replica rebuild** in compose (direct
  alias) or ECS (internal FQDN). No compose address change is needed.

## 5. Remaining unknowns

- **Kubernetes manifests:** none. The only `kind: Deployment` match is prose in this plan. `ops/` has no k8s/helm
  directory.
- **Billing HTML:** none. All portal controllers are JSON `@RestController`s under `/portal/api`; no template engine,
  no static HTML, no `@Controller`. The only HTML is springdoc's `/scalar`. The portal URL is for a frontend the
  Tenant hosts. Phase 11 moves only the API paths and the URL builders, not any page.
- **Booking root `/p/` alias:** the controller exists only at `@RequestMapping("/p")`; `/booking/p/` is an accepted
  security/filter spelling (`SecurityConfiguration:47-51`, `PublicGateFilter:74`), not a second mapping. Phase 10
  removes the root alias and the dual spellings.
- **Workflow `/hooks`:** the controller maps only `/hooks`; `SecurityConfiguration:42-49` permits both spellings.
  `TenantContextFilter:27`, `AppEntitlementFilter:24` and `SecurityConfiguration` also carry dead `"/t/"` /
  `"/workflow/t/"` matchers — Workflow has no `/t/` surface. Attribution carries a dead `"/attribution/t/"` matcher
  too. Drop them rather than rewrite them.
- **Where `x-public` is emitted:** `documentedAccess()` in Platform, Mailroom, Survey, Attribution, Booking,
  Customer, Loyalty and Workflow `WebConfiguration`s. **`core-openapi`'s `ErrorResponsesOpenApiCustomizer:112` reads
  `x-public`** to decide whether to derive `401`/`403`/`404`; phase 2 must switch that check to the new caller label
  before `x-public` is deleted, or public operations will silently gain error responses.
- **Billing `@Authenticated`:** none exists; the branch in `allowedCallers()` is dead today.
- **Billing portal session:** `PortalSessionController` mixes one `@PublicEndpoint` (`/portal/api/session/exchange`,
  single-use token) with `@RequiresPortalPermission` methods. The paths differ, so it is not the mixed-path stop
  condition, but the class-level `@Callers` needs a method-level exception.

## 6. Mixed-path check

**Clear.** No public controller shares a class or path with an authenticated operation.

| Public surface | Controller | Methods |
|---|---|---|
| Survey `/p/**` | `visit/web/PublicController`, `response/web/AnswerController` | all `@PublicEndpoint` |
| Mailroom `/t/**` (dual `/mailroom/t`) | `sending/.../PublicTrackingController` | all `@PublicEndpoint` |
| Attribution `/go/**`, `/t/v1/events` | `boot/web/RedirectController`, `boot/web/CollectController` | all `@PublicEndpoint` |
| Workflow `/hooks/**` | `triggers/.../HookController` | `@PublicEndpoint` |
| Billing Stripe `/stripe/webhooks/**` | `payment/.../StripeWebhookController` | `@PublicEndpoint` |
| Billing portal exchange `/portal/api/session/exchange` | `boot/portal/PortalSessionController` | `@PublicEndpoint` on that method only |
| Platform `/oauth/token`, `/.well-known/jwks.json` | `TokenEndpointController`, `JwksController` | `@PublicEndpoint` |

Booking's `/p/**` is public-only but unannotated (see item 3); split/annotation, not a path conflict.

## 7. Tooling for the matrix (phase 4)

- Node 26.10.0 is installed, but **`yaml` is not resolvable** from the repository root and is not a dependency of
  `frontend/` (`frontend/package.json` has no `yaml`, no hoisted copy). A `caller-matrix.mjs` would need a new
  dependency.
- **Ruby 4.0.7 with stdlib `yaml` works** and Ruby is already a documented prerequisite for the smoke scripts.
- Decision: write the generator as **`ops/docs/caller-matrix.rb`** (`--write` / `--check`) and run
  `make docs-check` with Ruby. GitHub's `ubuntu-latest` ships Ruby; if that ever fails, the fallback is
  `ruby/setup-ruby` in the workflow. No new Node dependency.
- Python 3 (used by `make docs` for the static server) has no `yaml` module, as the plan said.

## 8. Old-path inventory

The full file:line inventory is grouped by the phase that moves it. Summary:

- **Phase 6:** suite-token callers of `/api/v1` (item 9) plus every app's literal `/api/v1` `@RequestMapping`.
- **Phase 7:** `/api/v1/console` (Platform controller, console feed tests, `frontend/e2e/support/platform.ts:221`,
  `stack.ts:352`, `shell-core/src/boot.test.ts:29`, generated Platform client, `docs/console.md:54,183`);
  `/api/v1/notifications` (Notification controllers, smoke script, contract, docs);
  `/internal/replica` (Platform `SnapshotController:64`, 9 `SnapshotFeignApi`s, core-feign tests, generated client,
  `docs/platform-service.md:25`).
- **Phase 8:** no old-path literals; add the 404 rule to Caddy and the ALB.
- **Phase 9:** `/survey/p/` (5 security/filter/controller files, `LinkUrls:26`, contracts, Caddy, Terraform,
  compose, seed, docs, README); `/workflow/hooks` dual spelling (security config, controller, test, contract, Caddy,
  Terraform, docs).
- **Phase 10:** `/mailroom/t/` (dual `@RequestMapping({"/t","/mailroom/t"})`, filters, `MarketingTracking:98-130`,
  tests, contract, Caddy, Terraform, compose, docs); `/go/` and `/t/v1/events` (RedirectController,
  CollectController, static snippet file path `static/t/v1/snippet.js`, link builders, tests, contract, Caddy,
  Terraform, seed, docs); `/booking/p/` + root `/p/` (PublicController, filters, security config, tests, load test,
  hand-written contract, Caddy, ALB, smoke, compose, docs).
- **Phase 11:** `/portal/api/**` (6 portal controllers, session filter `PORTAL_PREFIX`/`EXCHANGE_PATH`, CORS, security,
  URL builders, tests, generated client); `/stripe/webhooks/**` (controller, `PaymentSettings:63,68`, security,
  tests, contract, generated client); `/portal/return-urls` under `/api/v1` **must not move** (tenant API).
- **Do not edit:** `docs/system-specification.md` — zero matches for any pattern.

## 9. Suite-token callers of `/api/v1`

All mints funnel through `POST /platform/oauth/token` with `grant_type=client_credentials&audience=<app>`
(`ops/smoke/lib/harness.sh:178-185`, `ops/seed/platform.sh:181-184`, `frontend/e2e/support/stack.ts:209-222`,
`frontend/e2e/support/seed-rich.ts:64-65`).

| Caller | Files | Target |
|---|---|---|
| Workflow HTTP step | `connections/.../HttpCallerService:75-83`, `runtime/.../OutboundHttp:14`, `WorkflowSettings:127-132` | app base URL + `/api/v1/...` |
| Workflow sample graphs | `app-boot/src/test/resources/samples/{erasure-fanout,payment-dunning,points-nudge,refund-approval}.json` | `/api/v1/{customers,loyalty,mailroom,emails,invoices,subscriptions,payments}` |
| Workflow tests | `runtime/.../HttpNodeTest:33,42` | `/api/v1/customers` |
| Seed | `ops/seed/{billing,customer,loyalty,mailroom,survey,workflow,attribution,booking}.sh` via `A_SYS` | `<app>/api/v1` |
| Smoke | `customer-smoke`, `loyalty-smoke`, `customer-loyalty`, `booking.sh`, `workflow.sh`, `workflow-chaos.sh`, `suite-smoke.sh:169-206` | `<app>/api/v1` (except suite-smoke: `${PROXY}/api/v1` for Billing) |
| Console CI client | `ops/smoke/console-smoke.sh:87-97` | `platform/api/v1/ui-remotes/{remote}/versions` (moves to `/admin/v1` in phase 7, caller `internal`) |
| E2E | `frontend/e2e/support/stack.ts:254-329`, `seed-rich.ts` (~30 calls) | Billing `/api/v1/*` |
| Docs examples | **none** with a suite token | — |

Not suite tokens (leave on `/api/v1`): `notification-smoke.sh` (Keycloak), platform smoke/e2e (Keycloak),
`ops/loadtest/booking.sh` (customer token → `/p/bookings`, phase 10 target).

## 10. Realtime plan order

Decided with the user on 2026-11-22: **this plan lands first**. If `261122-1519-suite-realtime-signals` lands
second, its stream route becomes a `tenant-user`/`platform-user` surface under `/api/v1` and its bell uses
`/api/v1/me/notifications`. This plan does not edit that plan.

## Stop conditions

| Condition | Result |
|---|---|
| A controller serves public and authenticated callers on one path | **Clear.** See item 6. |
| A no-permission endpoint returns per-caller data | **Clear.** Notification and `me` are self-service by `CurrentActor`; console remotes is scope-filtered. |
| An `/internal` edge block breaks replica rebuild | **Clear.** Compose and ECS both call Platform directly. |
| Spring cannot mount a second prefix cleanly, or springdoc duplicates `operationId`s | **Clear for mounting** (spike). **Open for ids:** springdoc de-dupes but renumbers the canonical `/api/v1` path; phase 5 must pin ids deterministically. |

## Carried into later phases

- Phase 2: shared catalog reader in `core-openapi`; make Notification package the catalogs and Loyalty package all;
  switch `ErrorResponsesOpenApiCustomizer` off `x-public`; decide how Billing's `@RequiresPortalPermission` maps to
  `customer-session` without a core→billing dependency (pluggable caller sources); annotate Booking's `/p` gate.
- Phase 3: Booking's contract test currently skips `/p/`; the label rules must cover it.
- Phase 5: pin operation ids; do not register a union-path mapping; register once.
- Phase 14 (optional): Loyalty does not forward `-Dopenapi.update`; only Platform and Billing have a generated frontend
  client, so operation-id churn for the other apps is client-free.
- Minor: `core-openapi` has a `StatusLinesOpenApiCustomizerTest` with no production counterpart; leave it alone unless
  phase 2 touches that area.
