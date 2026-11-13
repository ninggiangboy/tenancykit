---
title: "Share one Feign client across services and use it for Billing's Platform replica"
date: 2026-11-13
summary: "Add core-feign, move Billing's snapshot transport onto it, and refactor the tenant billing-client onto the same plumbing."
---

# Share one Feign client across services and use it for Billing's Platform replica

## What happened
- Added `core/core-feign`: `FeignClients`, `FeignSettings`, `FeignFailures`, `JacksonCodec`, `LinearRetryer`,
  `SpringMvcFeignContract`, `TokenProvider`, `TokenClient`, `TokenSettings`, `ClientCredentialsTokenProvider`,
  `StaticTokenProvider`, `StatusFeignErrorMapper` and `FeignJson`.
- `core-kernel` gained `UpstreamUnavailableException` and `UnauthorizedException`, so the shared client never throws
  one service's error type; each service passes its own `FeignFailures` mapping.
- Billing: `SnapshotClient.SnapshotTransport` is implemented by `FeignSnapshotTransport` under
  `boot/replica/feign`; `PlatformSnapshotTransport` (RestClient plus `Map` parsing) is deleted. `ReplicaConfiguration`
  builds the client from `suite.replica.*`; `max-attempts` (default 3) and `retry-backoff` (default `PT0.5S`) are new
  optional keys, and `request-timeout` feeds both Feign timeouts.
- Token: one `ClientCredentialsTokenProvider` per replica, cached until 30 seconds before expiry. A 401 drops the cache
  and retries exactly once, so a five-stream rebuild costs one token request instead of five.
- Tenant: `billing-client` builds on `core-feign`; `JacksonCodec`, `LinearRetryer`, `TokenClient`, `TokenProvider`,
  `PlatformTokenProvider`, `PlatformTokenSettings`, `StaticTokenProvider` and `HttpBillingSettings` are deleted, and
  `BillingErrorDecoder` plus `TenantFeignFailures` keep only the tenant's own mappings.
- ArchUnit: `core-is-self-contained` in billing and tenant excludes `core.feign..`, like the other framework modules.

## Verification
- `core`: `./gradlew build` green; `core-feign` has 18 tests covering the codec, retryer, error mapper, token client
  and token provider.
- `billing`: `./gradlew :apps:app-boot:cleanTest :apps:app-boot:test` green. Six `FeignSnapshotTransportTest` cases
  cover path and query shape, null cursor omission, three-attempt exhaustion, single-failure recovery, a final 4xx and
  the 401 refresh.
- `tenant`: `./gradlew build` green; `FeignBillingClientTest` (16 tests) passes without assertion changes.
- `platform`: `./gradlew build` green after the `core-kernel` additions.
- `ops/smoke/suite-smoke.sh` was not run: this checkout has no `.env`, and the script needs the Platform admin secret.

## Decision
- Bounded retry only. The snapshot rebuild runs once at boot and a container restart remains the outer retry, so a
  circuit breaker would reset with the process and add nothing.
- `core-feign` holds plumbing only; `core-replica` keeps its framework-light `SnapshotTransport` port, and the Feign
  adapter lives in the app's boot layer.

## Next steps
- No circuit breaker or load balancer. Revisit if Billing ever calls Platform per request rather than at rebuild.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
