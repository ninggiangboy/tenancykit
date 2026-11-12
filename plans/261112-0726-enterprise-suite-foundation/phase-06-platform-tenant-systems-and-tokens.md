---
phase: 6
title: "Platform: tenant systems and M2M tokens"
status: pending
priority: P1
effort: "3d"
dependencies: [5]
---

# Phase 6: Platform tenant systems, credentials and M2M tokens

## Goal

Move tenant systems and their API credentials from Billing to Platform. A tenant system exchanges its secret at `POST /oauth/token` for a 10-minute JWT whose audience is one app and whose scope holds only that app's granted codes.

## Key insights

- Billing's `ApiKey` format, hashing, rotation overlap and revocation (`billing/contexts/platform/src/domain/java/dev/ngb/billing/platform/domain/credential/`) move unchanged. The secret becomes the `client_secret` of a client-credentials grant; the key id is the `client_id`.
- Rate limiting moves with it: `Authentication.authenticateApiKey` and its anonymous-bucket logic, the `RateLimiter` port and `PostgresRateLimiter` (`billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/infrastructure/security/PostgresRateLimiter.java`) now guard the token endpoint instead of every API call.
- Revocation latency: a revoked credential's existing tokens stay valid until they expire (at most 10 minutes). Disabling a **tenant system** is immediate, because every app checks the replicated `tenant_systems.status` on each request (phase 7).
- Billing relies on tenant-system ownership (customers, billing sessions and webhook endpoints have composite FKs to `tenant_systems`). `tenant-system.changed` keeps those FKs satisfiable in every app.

## Files

Create in `platform/contexts/integration/` (package root `dev.ngb.platform.integration`): domain `tenantsystem` (`TenantSystem`, `TenantSystemStatus`, plus `version`) and `credential` (`ApiCredential`, `ApiCredentialId`, `ApiKey`, `CredentialStatus`, plus granted `scopes` per credential); application `tenantsystem`, `credential` (`ManageCredentialUseCase`, queries), `token` (`IssueTokenUseCase`: authenticate key, check tenant system active, filter scopes by audience, call `SuiteTokenIssuer`), `auth/port/RateLimiter`; adapter persistence, web (`TenantSystemController`, `CredentialController`), `security/PostgresRateLimiter`.

- `platform/apps/app-boot/src/main/resources/db/migration/V3__integration.sql`: `tenant_systems` (with `version`), `api_credentials` (as in Billing's `V4__identity.sql` lines 71 onwards, plus a `scopes text[]` column), `rate_limit_buckets` (as in Billing's `V16__runtime_coordination.sql`).
- Snapshot stream `tenant-systems`.

Modify:
- `platform/apps/app-boot/.../token/TokenController.java`: delegate tenant-system clients to `IssueTokenUseCase`; service clients keep the static path from phase 4.
- `contracts/tokens/README.md`: final claim list with an example token.

## Implementation steps

1. Copy domain and use cases from Billing; add `version`, `scopes` on credentials, and `tenant-system.changed` events.
2. Validate requested credential scopes against the catalogs: only codes with `grantable_to_tenant_system: true`.
3. Implement `IssueTokenUseCase` and wire it into the token endpoint; claims per `contracts/tokens/README.md`.
4. Rate-limit the token endpoint per key id, with the anonymous buckets for unknown ids.
5. Audit create, rotate, revoke and every failed token request.
6. Tests: a token for `audience=billing` carries only `billing:*` codes; a disabled tenant system gets 401; a revoked key gets 401; during rotation overlap both keys work; the 101st request in a window gets 429.

## Todo

- [ ] Tenant systems and credentials moved with versions and events
- [ ] Credential scopes validated against the catalogs
- [ ] Token issuing for tenant systems with audience filtering
- [ ] Rate limiting and auditing
- [ ] `tenant-systems` snapshot stream and tests

## Verification

- `cd platform && ./gradlew build` green.
- `curl -u "$KEY_ID:$SECRET" -d grant_type=client_credentials -d audience=billing http://platform.localhost:8090/oauth/token` returns a token whose payload (decoded with `jq -R 'split(".")[1] | @base64d | fromjson'`) has the expected claims.

## Risks

- A tenant system that cached a token keeps access for up to 10 minutes after key revocation. Accepted and documented; disabling the tenant system is the immediate kill switch.

## Security

- The secret is shown once, hashed at rest, never logged (unchanged rules from `docs/authentication-and-authorization.md`).
- The token endpoint answers the same 401 for unknown, wrong, revoked and expired keys.
