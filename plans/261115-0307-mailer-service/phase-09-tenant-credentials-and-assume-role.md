---
phase: 9
title: "Tenant Credentials and Assume-Role"
status: completed
priority: P2
effort: "L"
dependencies: [8]
---

# Phase 9: Tenant Credentials and Assume-Role

## Goal

Let a tenant send from its own AWS account: store a role ARN and an external id per tenant, assume that role
at delivery time, and detect a revoked role before it silently strands a tenant's email.

## Key Insights

- The pattern already exists for Stripe: `StripeConfigurationService.java:73` calls
  `provider.verifyKey(secretKey)` **before** writing the row, and `ProviderAccess.of(configuration)`
  (`billing/.../stripe/service/ProviderAccess.java:18-26`) chooses between the tenant's key and the platform's
  at call time. Copy both: verify-then-store, and resolve-per-call.
- `SecretCipher` (`core/core-kernel/.../security/SecretCipher.java`) with `AesGcmSecretCipher`
  (`core-spring`) is on mailer's classpath from phase 2. The context argument is the owning row's id, so a
  ciphertext moved to another row fails to decrypt.
- `StsAssumeRoleCredentialsProvider` caches credentials: `staleTime` defaults to 1 minute and `prefetchTime`
  to 5 minutes. One provider instance per `(roleArn, externalId)` can be shared across clients, and it must be
  `close()`d.
- **Role chaining caps the session at one hour.** The task role's credentials are already temporary, so
  assuming the tenant's role is chaining; asking for `durationSeconds > 3600` fails. The default of 3600 is
  the ceiling here, not a choice.
- The `ExternalId` is **not a secret** in the AWS threat model — anyone who can read the role sees it. Encrypt
  it anyway because it is per-tenant configuration, but do not treat it as an authentication factor on its own.
  What makes it useful is that AWS requires the caller to present it, which blocks the confused-deputy case.
- Region is a per-row property. SES identities exist per account **and region**, and a client built with the
  wrong region returns errors that look unrelated to the mistake.
- Under this mode, SES sees the tenant's account as the caller. Quota, reputation, suppression and bounce
  events are all theirs. That is the main trade-off and it is accepted in the plan, not fixed here.

## Files to Create / Modify

- Create: `mailer/contexts/email/src/application/java/dev/ngb/mailer/email/application/email/port/CredentialProbe.java`
  — `ProbeResult probe(String roleArn, String externalId, String region)`
- Modify: `mailer/contexts/email/src/adapter/.../email/credential/ConfiguredSenderResolver.java`
  — **extend this class; do not add a parallel resolver.** It gains the `TENANT` branch and a bounded,
  evictable provider cache keyed by `(credentialRowId, version)`. Three phases must not produce three
  implementations of one lookup.
- Create: `mailer/contexts/email/src/adapter/.../email/credential/CredentialProviderCache.java`
  — bounded cache of `StsAssumeRoleCredentialsProvider` keyed by `(rowId, version)` with an explicit
  `evict(rowId)`, called on disable, on rotation and on any `AccessDenied` from a send
- Create: `mailer/contexts/email/src/adapter/.../email/credential/SesCredentialProbe.java`
  — assume the role, then call `GetAccount` (which returns the send quota)
- Create: `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/jobs/CredentialHealthJob.java`
  — re-probe rows whose `last_verified_at` is stale, emit a metric and a WARN
- Create: `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/security/MailerSecretCipherConfiguration.java`
  — `AesGcmSecretCipher` with prefix `mailer.secrets`
- Create: `mailer/contexts/email/src/adapter/.../email/credential/CredentialAdminUseCase.java`
  — ops-only entry point (ops-invoked, no HTTP), registering and disabling a tenant credential
- Modify: `mailer/contexts/email/src/adapter/.../email/credential/ConfiguredSenderResolver.java`
  — see the file list above; this is the single resolver
- Modify: `mailer/apps/app-boot/src/main/resources/application.yml` — `mailer.secrets.master-key`,
  `mailer.secrets.master-key-id`, `mailer.credentials.health-interval`
- Create test: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/email/CredentialResolutionTest.java`
- Create test: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/email/CredentialProbeTest.java`

## Architecture

```
resolve(tenantId, fromKey)
  → row = mailer_credentials where tenant_id = ? and from_key = ? and status = 'ACTIVE'
      none        → SHARED defaults (configured region + from address + DefaultCredentialsProvider)
      SHARED      → that row's region + from address + DefaultCredentialsProvider
      TENANT      → StsAssumeRoleCredentialsProvider(
                      StsClient(region = row.region),
                      AssumeRoleRequest(roleArn = row.role_arn,
                                        externalId = decipher(row.external_id_ciphertext, row.id),
                                        roleSessionName = "mailer-" + tenantId,
                                        durationSeconds = 3600))
  → ResolvedSender(region, credentialsProvider, fromAddress(row.from_address, row.from_name))
```

`CredentialProbe` runs the same assume-role and then two read-only SES calls. Success writes
`last_verified_at`; failure raises `ProviderRejectedException`-style error to the caller and writes nothing.
A revoked role later surfaces as `AccessDenied` at send time, which phase 8 maps to `Rejected`, so the
delivery dies immediately instead of burning the retry budget — and `CredentialHealthJob` catches it first.

## Implementation Steps

1. Write `CredentialProbe` and its SES implementation: `assumeRole` (no session token caching needed for a
   one-shot probe), then `GetAccount` on a `SesV2Client` built with the assumed
   credentials and the row's region. Return the quota so onboarding can record it.
2. Write the `TENANT` branch of `ConfiguredSenderResolver`: resolve the row, decipher the external id, and
   build or fetch a cached provider for `(rowId, version)`. `roleSessionName` includes the row version so
   CloudTrail distinguishes a rotated credential from the old one.
3. Write `CredentialProviderCache` with a size bound and an explicit `evict(rowId)`. Call it from the admin use
   case on disable and on rotation, and from the sender on `AccessDenied`. **A disabled credential that keeps
   sending until the process restarts is not a control.**
4. Write `CredentialAdminUseCase` with optimistic locking: every write is
   `update … where id = ? and version = ?` and bumps `version`. Rotation inserts the new row at
   `version + 1` before disabling the old one — the partial unique index is on `(tenant_id, from_key, version)`
   precisely so both rows can exist during the swap. Reject a `role_arn` already registered to a different
   tenant, and reject an `aws_account_id` that does not match the role ARN's account.
5. Write `CredentialProbe` and its SES implementation: `assumeRole` (one-shot, no cache), then `GetAccount`
   on a client built with the assumed credentials and the row's region. Return the quota so
   onboarding can record it.
6. Write `CredentialHealthJob`: select rows with `last_verified_at < now - interval`, probe each, update with
   the same `version` guard, evict the cache entry on failure, and emit a counter. It holds a row lock only for
   its own update, so it cannot race the delivery path's read.
7. Wire `AesGcmSecretCipher` with the master key from the environment.
8. Write `CredentialResolutionTest`: no row → SHARED defaults; `SHARED` row → that row's from address;
   another tenant's row is invisible; `DISABLED` row → error, not fallback; bad ciphertext context →
   decryption fails; `TENANT` row resolves a provider without calling AWS (fake STS); eviction makes the next
   resolve rebuild.
9. Write `CredentialProbeTest` against a stubbed STS/SES: success writes `last_verified_at`; failure writes
   nothing and throws.

## Todo

- [x] `CredentialProbe` port + SES implementation (`assumeRole`, then `GetAccount`)
- [x] `TENANT` branch in the existing `ConfiguredSenderResolver`, region from the row
- [x] `CredentialProviderCache` keyed by `(rowId, version)` with `evict(rowId)`
- [x] `CredentialAdminUseCase` with version CAS, rotation at `version + 1`, account-allowlist check
- [x] `CredentialHealthJob` with the same version guard and cache eviction
- [x] `AesGcmSecretCipher` wired with the environment master key
- [x] `CredentialResolutionTest` incl. cross-tenant invisibility and `DISABLED` → error not fallback
- [x] `CredentialProbeTest` against a stubbed STS/SES

## Verification

```bash
cd mailer && ./gradlew :apps:app-boot:test --tests '*CredentialResolutionTest' --tests '*CredentialProbeTest'
cd mailer && ./gradlew build
```

And the negative case that matters, documented in the PR:

```sql
-- a TENANT row without a role_arn must be impossible
insert into mailer_credentials (tenant_id, from_key, mode, status, version)
values (gen_random_uuid(), 'marketing', 'TENANT', 'ACTIVE', 1);
-- must fail on the check constraint
```

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| Provider built per delivery | STS throttling and a latency spike per send | Cache per `(roleArn, externalId)`; close on shutdown |
| Asking for `durationSeconds > 3600` | `ValidationError` from STS on every send | Chaining caps it at 3600; that is the ceiling |
| Client region taken from config, not the row | `MessageRejected` or an identity-not-found error for a tenant in another region | Region comes from the row, always |
| `AccessDenied` retried | The ledger climbs to `maxAttempts` before dying | Already `Rejected` in phase 8; assert it in a test |
| Ciphertext copied to another row | Decryption succeeds when it should not | The context must be the row id; a test asserts failure |
| Health check missing | A revoked role produces a wall of `DEAD` rows before anyone notices | Ship the job with the mode, not later |

## Security Considerations

- Only `role_arn`, `region` and the encrypted `external_id` are stored. Never an access key or secret key —
  that is the entire reason this mode was chosen over static credentials.
- The resolver's credentials provider must never be logged.
- The tenant's trust policy must name our account **and** require `sts:ExternalId`; the onboarding document
  in phase 10 ships that policy verbatim.
- Commands a tenant's role must allow are read-only for the probe (`GetAccount`) and
  `SendEmail` for delivery.

## Next Steps

Phase 10 deploys the service, creates the task role and the SES identity, and documents the tenant-side
onboarding.
