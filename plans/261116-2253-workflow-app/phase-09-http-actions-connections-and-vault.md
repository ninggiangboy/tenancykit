---
phase: 9
title: "HTTP actions, connections and credential vault"
status: in-progress
priority: P1
effort: "L"
dependencies: [6]
---

# Phase 9: HTTP Actions, Connections and Credential Vault

## Goal

An `http` node calls a suite app as the Tenant or an external system through a Tenant-owned connection, with encrypted write-only
secrets, token minting per audience, a stable idempotency key, retry with backoff, an SSRF guard, redacted history and hard
limits on time and size.

## Context

Apps build their tenant from the host alone, so a call to an app must use the Tenant's hostname (replicated `tenant_domains`),
through the gateway or with a `Host` header against an internal address. Tokens: one per audience, ten minutes, requested from
Platform `POST /oauth/token` with `grant_type=client_credentials&audience=<app>` and HTTP Basic using the credential
(`contracts/tokens/README.md`). Reuse `core/core-webhook` `DestinationPolicy`, `GuardedDnsResolver`, `WebhookHttpSender` and
`RetrySchedule` rather than writing new guards. Only the `connections` context may open outbound HTTP connections.

## Files to create

- Migration `V6__connections.sql`: `connection` (tenant, name, kind `BEARER|BASIC|API_KEY|HMAC|NONE`, allowed host pattern,
  encrypted secret, key id, last verified), `platform_credential` (one per Tenant: client id, encrypted secret, key id, status
  `ACTIVE|INVALID`), RLS
- `workflow/contexts/connections`: domain (`Connection`, `SecretBox` AES-256-GCM with key id), application (`RegisterCredential`,
  `ManageConnection`, `TokenProvider`, `HttpCall`), adapter (controller, Platform token client, outbound sender)
- OpenAPI: `PUT /api/v1/platform-credential` (write-only secret), `GET` (status, masked id), `connections` CRUD
  (`workflow:connection:manage`); `application.yml` keys for app base URLs and `WORKFLOW_SECRET_KEY`/`..._KEY_ID`
- `runtime`: `HttpNodeExecutor`; tests

## Tasks

1. `SecretBox`: AES-256-GCM, random nonce per secret, key from the environment, a key id stored with the ciphertext so a key can
   rotate (old keys stay readable until re-encrypted). Secrets never appear in `toString`, logs, errors or history.
2. Registering the Platform credential: the Tenant creates a `WORKFLOW`-purpose Tenant System and credential in Platform
   (phase 2) and registers its id and secret here. A token request that returns 401 marks the credential `INVALID`; steps then
   fail with `credential_invalid` without hammering Platform.
3. `TokenProvider`: per `(tenant, audience)` cache until about a minute before `exp`, one in-flight request per key, token
   endpoint `429` honours `Retry-After`.
4. `HttpNodeExecutor`: build the call from the node (target app or connection, method, path with params, query, headers, body,
   all as expressions), add `Idempotency-Key` from phase 6, send with timeout (default 30 s), response cap (1 MB) and the
   retry policy (phase 12 extends it); map the response into the step output by an expression over status, headers and body.
5. App calls: path and method were checked against the app's OpenAPI at publish; at run time the base address comes from
   configuration and the Tenant's hostname from the replica; refuse if the Tenant has no active domain.
6. External calls: the connection fixes the allowed host pattern; `DestinationPolicy` and the DNS guard reject loopback,
   link-local, private and metadata ranges, and a redirect is followed only to a destination that passes the same check.
7. History stores a redacted request and response (authorization, cookies, connection secret values, configured header names).

## Validation

- Tests: decrypt after a key rotation; no secret in any log capture or history row; token reused until near expiry and
  refreshed once under 50 concurrent calls; 401 flips the credential to `INVALID`.
- SSRF suite: `localhost`, `127.0.0.1`, `169.254.169.254`, `[::1]`, a name resolving to `10.0.0.1`, a redirect to each of those,
  and an allowed-host mismatch are all refused with no connection made.
- On the compose stack a workflow calls CRM (list customers) and mailroom (send one email) as the Tenant through Caddy.
- ArchUnit: no outbound HTTP outside `connections`.

## Risks

- The engine concentrates credentials: write-only secrets, encrypted at rest, RLS, redaction and the secret-scan test are the
  controls; the plan's risk register records the residual risk.
