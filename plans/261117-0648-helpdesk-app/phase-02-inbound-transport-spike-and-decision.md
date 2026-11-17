---
phase: 2
title: "Inbound transport spike and decision"
status: pending
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Inbound Transport Spike and Decision

## Goal

Choose, with evidence, how a customer's email reaches helpdesk's signed inbound endpoint for a Tenant's own domain, before
any inbound code is written, and record the decision and its limits. No production code is added in this phase.

## Context

The user left the mechanism open ("recommend one"). The recommendation is a thin adapter that posts a signed raw MIME to
helpdesk, with the choice of SES receiving or an SMTP listener made here. The open risk (see `plan.md`, risk register) is that
SES receiving requires the domain to be verified in the receiving AWS account while a Tenant sends through its own AWS role
(`docs/mailer-tenant-onboarding.md`). Phase 17 builds whichever option wins; phases 15 and 16 are independent of it because
they only see the signed endpoint.

## Questions the spike must answer, each with a recorded command or document

1. Can the suite's AWS account receive mail for a Tenant-owned domain with only an MX record and a TXT proof, with no
   delegation of the Tenant's AWS account? Which AWS region constraints apply to SES receiving?
2. If yes, what is the delivery path (SES receipt rule to S3 and SNS, then which component posts to helpdesk) and what does it
   cost to run on the suite's ECS (`ops/terraform`)? If no, what does an SMTP listener need (a public port 25, TLS, a
   container, anti-abuse limits, the Java library or a small Go/Python receiver)?
3. Do `SPF`, `DKIM` and `DMARC` results arrive in a form the adapter can put in `authResults`?
4. Does the local stack have a way to play a customer: Mailpit does not accept mail for relay, so the injector must write
   directly to the signed endpoint or to a local SMTP listener; which one is simplest and still exercises the real parser?
5. Which parse library parses MIME in helpdesk (Jakarta Mail, Apache James mime4j, or the already-present Spring mail
   dependency's `MimeMessage`)? Record the decision, memory use for a 25 MB message and charset handling.
6. Confirm by a throwaway test, not a document, that mailer carries `support+t1.abcdef@tenant-domain` as `replyTo` for a
   Tenant whose credential has an empty allowlist, locally through Mailpit and in SES sandbox terms.

## Files to create / modify

- Create: `plans/reports/helpdesk-inbound-transport-decision.md` (the answers, the chosen option, the rejected options and why)
- Modify: `docs/helpdesk.md` (inbound section), `plans/261117-0648-helpdesk-app/plan.md` (resolution entry)
- Throwaway: a scratch directory outside the repository for the experiments; nothing is committed from it

## Tasks

1. Read `ops/terraform/mailer/**`, `docs/mailer-tenant-onboarding.md` and the SES documentation for receipt rules; record what
   is verified from source and what only from documentation.
2. Run the experiment for question 6 against the compose stack (mailer plus Mailpit) and keep the output.
3. Write the decision report and the resolution into `plan.md`. If the decision changes the adapter's runtime (a container that
   needs a public port), list the Terraform and compose consequences for phases 3 and 17.
4. If SES receiving cannot serve Tenant-owned domains and the SMTP listener needs a public port 25 that the deployment target
   forbids, **stop and ask**: the remaining choices change the deployment.

## Verification

- The report answers all six questions or names the exact missing fact and who must supply it.
- Question 6 has captured evidence that the `replyTo` is accepted and the sent message carries it.
- `git status` shows no change outside `docs/helpdesk.md`, `plans/` and nothing under any app.

## Risks

- Documentation of SES receiving is read, not exercised: the decision states which parts were exercised and which are
  assumptions, so phase 17 can verify the rest first.
