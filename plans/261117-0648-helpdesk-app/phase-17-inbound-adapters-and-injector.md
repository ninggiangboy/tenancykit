---
phase: 17
title: "Inbound transport adapters and the local injector"
status: pending
priority: P1
effort: "M"
dependencies: [2, 15]
---

# Phase 17: Inbound Transport Adapters and the Local Injector

## Goal

Real email can reach helpdesk's signed endpoint in a deployment by the transport chosen in phase 2, and the compose stack has an
injector that plays a customer, so the smoke script exercises the real parser and the real signing.

## Context

Everything here is outside `helpdesk/` application code: an adapter is a small separate deployable (or Terraform plus a
function) that turns a received message into the signed JSON envelope. Phase 2's decision report names the transport. The
injector is the only path the local stack has, because Mailpit does not accept mail for relay; it must sign requests exactly as
the real adapter does so the signature path is never bypassed in tests.

## Files to create (final paths depend on the phase 2 decision)

- `ops/helpdesk-inbound/**`: the adapter (language chosen in phase 2), its Dockerfile, its signing code, its tests; or
  `ops/terraform/helpdesk-inbound/**` for SES receiving (receipt rule set, S3 bucket, SNS topic, the forwarder and its IAM)
- `ops/smoke/lib/inbound-mail.sh`: the injector, taking a raw `.eml` file and the Tenant domain, producing the signed request
- `docker-compose.yml`: the adapter service when it runs as a container (SMTP listener option) with its signing key
- `docs/helpdesk.md` (deployment section: DNS records, network restriction, key rotation)

## Tasks

1. Build the adapter chosen in phase 2. It must: authenticate to helpdesk with the inbound key and key id, retry on `503` and
   network errors with backoff, set `adapterMessageId` to a stable value so a retry never duplicates, include `authResults`,
   and never log message bodies.
2. Restrict the inbound route in a deployment: the load balancer or security group admits only the adapter, in addition to the
   signature; document it in `docs/helpdesk.md`.
3. Write the injector and use it for every `.eml` in `contracts/helpdesk/inbound-mail-vectors/`.
4. Add a key rotation procedure: add a second key, switch the adapter, remove the first; test it with the injector.
5. Terraform (or compose) changes are append-only and reviewed against `ops/terraform/README.md`.

## Verification

- With the stack up, the injector posts a vector and a ticket appears for the right Tenant; a message for an unverified domain
  is refused; a message signed with a retired key id is `401` after rotation.
- A duplicate delivery of the same adapter message id creates one ticket.
- The adapter's logs contain no body, address or attachment content (grep over a run).
- `terraform validate` passes for any Terraform added; compose `config` is valid.

## Risks

- If phase 2 chose SES receiving, some parts (a real MX record, a receipt rule) cannot be exercised on a developer machine: the
  verification states which were exercised and which were reviewed only, so the first deployment checks the rest.
