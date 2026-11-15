---
phase: 8
title: "SES Adapter and Shared Mode"
status: completed
priority: P1
effort: "M"
dependencies: [7]
---

# Phase 8: SES Adapter and Shared Mode

## Goal

Add a second `EmailSender` implementation that calls SESv2 with SigV4, and wire the `SHARED` credential mode
so the suite's own identity can send without any tenant configuration.

## Key Insights

Verified during planning, because both were open questions:

- **AWS SDK v2 does not put Jackson 2 on the classpath.** It depends on
  `software.amazon.awssdk:third-party-jackson-core`, which shades `com.fasterxml.jackson.core` into
  `software.amazon.awssdk.thirdparty.jackson.core`, and its POM uses `createDependencyReducedPom`, so nothing
  leaks transitively. There is no conflict with this repository's Jackson 3 (`tools.jackson`) and no
  `<exclusion>` is needed. This holds for SDK **≥ 2.17.0**; at 2.16.x and below the SDK pulls real Jackson 2.
- **SES SMTP cannot use temporary credentials.** AWS documents it explicitly: "Don't use temporary AWS
  credentials to derive SMTP credentials." An assumed role therefore cannot authenticate over SMTP, which is
  why this plan needs two adapters rather than one.
- The SDK **retries by default** (3 total attempts since 2.58.0). The ledger already retries with exponential
  backoff, so the adapter must disable SDK retries. Otherwise one ledger attempt becomes three API calls and
  the observable attempt count lies.
- `AccessDenied` is **not** modelled as its own exception for `SendEmail`; it arrives as the base
  `SesV2Exception` with HTTP 403 and error code `AccessDeniedException`. Mapping by exception class alone
  misses it and would retry a permanent failure three times, then keep retrying it from the ledger.
- The SDK request is `SendEmailRequest` with `Content.simple(...)`, `Body.html/text`, `Destination.toAddresses`,
  and the response carries only `messageId()`. A 200 means **accepted**, not delivered.
- The credential table already exists from phase 4, so `SHARED` needs data and a resolver, not new schema.

## Files to Create / Modify

- Modify: `mailer/gradle/libs.versions.toml` — `aws-sdk = "2.59.1"`, `aws-sdk-bom`, `sesv2`, `sts`
- Modify: `mailer/contexts/email/build.gradle.kts` — `adapterImplementation(platform(libs.aws.sdk.bom))`,
  `adapterImplementation(libs.aws.sesv2)`
- Create: `mailer/contexts/email/src/adapter/.../email/ses/SesV2EmailSender.java`
- Create: `mailer/contexts/email/src/adapter/.../email/ses/SesErrorTranslator.java` — HTTP status/error code → `SendOutcome`
- Create: `mailer/contexts/email/src/adapter/.../email/ses/SesClientFactory.java` — builds a `SesV2Client` per
  `(region, credentialsProvider)`, caches, closes on shutdown; honours a property-driven
  `endpointOverride` so the **same** adapter can be pointed at a local emulator or a stub without a third
  implementation
- Create: `mailer/contexts/email/src/application/.../email/port/SenderResolver.java`
  — `ResolvedSender resolve(TenantId tenantId, FromKey fromKey, String replyTo)` returning region,
  credentials provider, from address, from name and the validated reply-to. **One resolver for the whole
  plan:** phase 7 writes a stub reading configuration, this phase gives it the `SHARED` behaviour, phase 9
  adds `TENANT`. Do not create `SharedCredentialResolver` here and delete it in phase 9.
- Create: `mailer/contexts/email/src/adapter/.../email/credential/ConfiguredSenderResolver.java` — reads
  `mailer_credentials` for `(tenant_id, from_key, status='ACTIVE')` **with the tenant predicate**; falls back
  to the configured suite defaults only when no row exists *and* the tenant has no disabled row for that key
- Create: `mailer/contexts/email/src/adapter/.../email/persistence/JooqCredentialRepository.java`
- Modify: `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/config/EmailConfiguration.java` — select the
  sender by `mailer.sender` (`smtp` | `ses`)
- Modify: `mailer/apps/app-boot/src/main/resources/application.yml` — `mailer.sender`, `mailer.ses.region`,
  `mailer.ses.default-from-address`
- Create test: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/email/SesErrorTranslationTest.java`

## Architecture

```java
// SesV2EmailSender — retries disabled, because the ledger owns retry
SesV2Client client = SesV2Client.builder()
        .region(Region.of(resolved.region()))
        .credentialsProvider(resolved.credentialsProvider())
        .overrideConfiguration(o -> o.retryStrategy(RetryStrategy.none()))
        .build();

SendEmailResponse response = client.sendEmail(SendEmailRequest.builder()
        .fromEmailAddress(resolved.fromAddress())
        .destination(d -> d.toAddresses(email.recipient()))
        .replyToAddresses(email.replyTo())        // omitted when null
        .content(c -> c.simple(m -> m
                .subject(s -> s.data(email.subject()).charset("UTF-8"))
                .body(b -> b.html(h -> h.data(email.html()))
                            .text(t -> t.data(email.text())))))
        .build());
return new SendOutcome.Accepted(response.messageId());
```

Error translation, driven by the HTTP status and the error code rather than the exception class:

| Condition | Outcome |
|---|---|
| `statusCode == 429`, `isThrottlingException()`, `statusCode >= 500`, `isRetryableException()` | `Transient` |
| `403` or error code `AccessDeniedException` | `Rejected` — credentials or IAM are wrong |
| `MessageRejectedException`, `MailFromDomainNotVerifiedException`, `SendingPausedException`, `AccountSuspendedException` | `Rejected` |
| Other `4xx` (`BadRequestException`, `NotFoundException`) | `Rejected` |
| `SdkClientException` (transport, TLS, timeout) | `Transient` |

## Implementation Steps

1. Add the AWS SDK BOM and `sesv2` as `adapterImplementation` in the email context only, following how Stripe
   is scoped in `billing/contexts/payment/build.gradle.kts:15-16`. The port must not name an SDK type.
2. Write `SesClientFactory` that caches one client per `(region, credentials identity)` and closes them on
   shutdown. Do not build a client per send.
3. Write `SesErrorTranslator` using `statusCode()` and `awsErrorDetails().errorCode()`, in that order, with
   the class check only as an additional terminal signal.
4. Write `SesV2EmailSender` using the translated result. Include `Charset` explicitly so a non-ASCII subject
   survives.
5. Write `ConfiguredSenderResolver`. **Never look a credential up without the tenant predicate** — a lookup
   of "any `SHARED` row" lets tenant A send with tenant B's from address under the suite identity. A missing
   row falls back to the configured suite defaults; a row that exists but is `DISABLED` is an error, not a
   fallback, because silently switching identity is worse than failing.
6. Resolve `replyTo` against the row's `reply_to_allowlist` (phase 4): if the producer's `replyTo` is present
   and not covered, `Rejected`. This belongs in the resolver, not the adapter, so both transports get it.
7. Wire the sender behind `mailer.sender`, so `smtp` stays the local default and `ses` is what the container
   profile uses.
8. Write `SesErrorTranslationTest` covering each row of the table, including a 403 with the base exception
   type — that is the case the naive mapping misses.

## Todo

- [x] AWS SDK BOM + `sesv2` as `adapterImplementation` only
- [x] `SesClientFactory` caching per `(region, provider)`, closed on shutdown
- [x] `SesErrorTranslator` keyed on status code and error code, including 403 on the base exception type
- [x] `SesV2EmailSender` with `RetryStrategy.none()` and explicit charset
- [x] `SenderResolver` port + `ConfiguredSenderResolver` with the tenant predicate
- [x] `replyTo` allowlist enforced in the resolver
- [x] Sender selected by `mailer.sender`
- [x] `SesErrorTranslationTest` covering every row of the mapping table
- [x] `SesWireContractTest` (WireMock/MockWebServer) driving 429 / 403 / MessageRejected / 500 against the real adapter
- [x] `SesEmulatorSmokeTest` (tagged, skipped by default) against AWS Emulator via `endpointOverride`; a 501 is reported as "not covered", not a failure
- [x] `EMULATOR_TELEMETRY=off` set in CI and local scripts; self-signed HTTPS certificate trusted by the test client
- [x] `mailer.ses.endpoint-override` property threaded into `SesClientFactory`
- [x] Dependency grep proves no `com.fasterxml.jackson.core` on the classpath

## Verification

```bash
cd mailer && ./gradlew :apps:app-boot:test --tests '*SesErrorTranslationTest'
cd mailer && ./gradlew build
cd mailer && ./gradlew :apps:app-boot:dependencies --configuration runtimeClasspath | grep -i jackson
```

The last command must show **no** `com.fasterxml.jackson.core:jackson-databind`. If it does, an SDK version
below 2.17.0 has been resolved.

A real send needs an AWS account and a verified identity; that is checked in phase 10 against the deployed
service, not here.

## Testing Without A Real Account

Use **AWS Emulator** (`docs.aws.amazon.com/emulator`, source at `github.com/aws/aws-emulator`,
CC0-1.0). It is free, maintained by AWS, has one crate per service generated from the published API model,
and — the property that matters most — **an operation it does not implement returns HTTP 501
`NotImplemented`** instead of a silently wrong answer. That makes it safe in CI: you find a gap immediately
rather than six months later.

Three documented limits shape how far it can be trusted, and AWS states all three itself:

1. **"Don't use it for production workloads or to test security."** The user guide spells out why: *"The
   emulator doesn't evaluate IAM policies and resource-based policies the way AWS does."* So it cannot prove
   that a tenant's trust policy admits our account, nor that `sts:ExternalId` is required. That is exactly the
   security core of `TENANT` mode.
2. **One Region and one account.** *"The emulator accepts any Region and account ID in a request, but it
   doesn't keep resources separate by Region or account."* Cross-account isolation cannot be modelled at all,
   so `AssumeRole` succeeding here proves the call plumbing, not the boundary.
3. **Idle-timeout / test-scoped use.** All state is in memory and is discarded when the process stops. Start
   it for the test run, stop it after.

Practical friction for this project specifically:

- It serves **HTTPS on `127.0.0.1:9297` with a self-signed certificate**. The AWS SDK for Java v2 will reject
  that unless the test either adds the certificate to a trust store or supplies a custom HTTP client. Budget
  for that; it is the one thing that will not "just work" like LocalStack's plain HTTP.
- On macOS (this developer machine) the supported path is Docker; the Linux package is the native path.
- Telemetry is **on by default**. Set `EMULATOR_TELEMETRY=off` (or `--telemetry off`) in CI and in local
  scripts, so no run reports usage data outside the team's control.
- Whether `sesv2` has a crate at all must be confirmed at runtime, not assumed: point the client at it once
  and see whether `SendEmail` returns 200 or 501.

So the split of proof is:

| Concern | Proved by |
|---|---|
| Request shape, client construction, signing, `messageId` path | AWS Emulator (with `endpointOverride`) |
| Every error branch: 429, 403, `MessageRejected`, 500, transport timeout | `SesWireContractTest` (WireMock/MockWebServer) — an emulator will not inject these on demand and does not model IAM denial |
| `GetAccount` / quota | Real account, or the wire stub. Emulator coverage is unconfirmed |
| Cross-account trust policy + `ExternalId` enforcement | **Real account only** |
| Deliverability, sandbox rules, DKIM/MAIL FROM, bounce/complaint | Real account, mailbox simulator |

Tests to add in this phase:

- `SesWireContractTest` — asserts the exact JSON body `SesV2EmailSender` emits and returns, per scenario, 429
  (`TooManyRequestsException`), 403 with error code `AccessDeniedException`, `MessageRejected`,
  `MailFromDomainNotVerified` and a 500. This is what proves `SesErrorTranslator`.
- `SesEmulatorSmokeTest` — tagged and skipped by default; starts AWS Emulator, points the production adapter
  at it through `endpointOverride`, and asserts a `messageId` comes back. A 501 is a valid outcome and should
  be reported as "not covered", not as a failure.

`SesClientFactory` must accept `mailer.ses.endpoint-override` (empty in production) so both tests use the
production adapter unchanged. Local runs need static dummy credentials (`AWS_ACCESS_KEY_ID=test`), because
the SDK always signs and the ECS credential endpoint is unreachable.

What still needs a real account, and is checked in phase 10: verified-identity enforcement, sandbox limits,
real `GetAccount`, DKIM/MAIL FROM status, and bounce/complaint events. AWS's mailbox simulator
(`success@`, `bounce@`, `complaint@`, `ooto@`, `suppressionlist@simulator.amazonses.com`) lets that happen
**inside the sandbox**, which means the AWS contract can be proven before production access is granted.

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| SDK retries left on | `email_delivery_attempts` count is lower than the API calls billed | Set `RetryStrategy.none()` on the client |
| Jackson 2 leaks onto the classpath | The dependency grep shows `com.fasterxml.jackson.core` | Check the resolved SDK version; it must be ≥ 2.17.0 |
| 403 mapped to `Transient` | A revoked role produces endless retries and `DEAD` only after the full budget | Map 403 by status code, not by exception type |
| Client built per send | Connection churn, throttling at moderate volume | Cache per `(region, provider)` |
| Task role assumed to exist | `AccessDenied` on the first production send | Phase 10 creates it; do not deploy this phase alone |
| An emulator used as the only proof | Every test green, production refuses the cross-account role because the emulator never checked `ExternalId` | AWS Emulator's own docs say not to test security with it; `TENANT` mode is proven on a real account (phase 10) |
| Emulator's self-signed HTTPS rejected by the SDK | `SSLHandshakeException` on every call | Add the certificate to the test trust store or supply a custom HTTP client; do not disable verification in production code |
| `GetSendQuota` called on SESv2 | `NoSuchMethodError` / unknown operation | SESv2 has no such action; quota comes from `GetAccount().sendQuota()` |

## Security Considerations

- The task role's `ses:SendEmail` permission must name the identity ARN, never `*`.
- Credentials come from `DefaultCredentialsProvider`; never read access keys from the environment.
- Log the SES `messageId` and the error code, never the message body or the credentials.
