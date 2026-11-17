---
phase: 19
title: "End-to-end smoke, isolation, ops and docs"
status: pending
priority: P1
effort: "L"
dependencies: [8, 10, 13, 14, 16, 17]
---

# Phase 19: End-to-End Smoke, Isolation, Ops and Docs

## Goal

The whole helpdesk is proven against the compose stack by a script that plays the customer and the Tenant System, isolation and
security are checked across every table and route, and operations and documentation are complete.

## Files to create / modify

- Create: `ops/smoke/helpdesk.sh` (and `ops/smoke/helpdesk-invariants.sh` plus `.sql` for database invariants, as booking has)
- Modify: `ops/smoke/isolation.sh`, `ops/smoke/suite-smoke.sh` (add helpdesk to the suite run, append-only)
- Create: `ops/loadtest/helpdesk.sh` (inbound burst, form burst, SLA sweep with 10,000 timers)
- Create: `ops/terraform/helpdesk/**` (ECS services api and worker, secrets, target group, as `ops/terraform/customer`)
- Modify: `docs/helpdesk.md` (final), `docs/runtime-profiles.md`, `docs/security-and-reliability.md`, `docs/multi-tenancy-and-data-isolation.md`
  (the inbound tenant-from-domain rule), `docs/mailer-tenant-onboarding.md` (support address and reply-to allowlist),
  `README.md`, `contracts/README.md`

## Smoke scenarios (one script, each a named step with a pass line)

1. Enable the entitlement, create a Tenant domain, verify it (TXT stub in the local DNS checker), configure mail settings.
2. **Email in:** inject a plain message, a multipart with attachment, a message to a queue address; each becomes a ticket in the
   right queue with its attachment downloadable.
3. **Email out and thread:** an agent replies, once with an attached file (assert the Mailpit message carries the part); capture the mail in Mailpit; check `Reply-To` token and subject token; inject the
   customer's answer built from that Reply-To; assert it is a message on the same ticket and no new ticket exists.
4. **Loop safety:** inject an auto-reply, a bounce and a list mail; assert no ticket and no `email.send`.
5. **Form:** submit; assert ticket, confirmation in Mailpit, tracking link read and reply; assert the daily cap and the one-hour
   dedupe of the confirmation.
6. **Phone:** an agent creates a phone ticket; a reply is refused; a note works.
7. **Generic channel:** push a message, reply as an agent, receive `ticket.replied` at the Tenant's webhook receiver with the
   conversation reference; push again to the closed conversation and see a follow-up.
8. **Assignment and merge:** round-robin across three agents; merge two tickets; assert counts and the source closed.
9. **SLA:** policy with a one-minute first response; wait; assert exactly one `sla.at-risk` and one `sla.breached`, also across
   a worker `SIGKILL` and restart; pause on `PENDING` and resume.
10. **Auto-close:** set `autoCloseDays` to a tiny value in a test setting, solve, and assert `ticket.closed` once.
11. **Isolation:** a second Tenant cannot read any of the above; inbound mail for the first Tenant's domain never reaches the
    second; the database invariants SQL finds no orphan, duplicate ticket number or message without a ticket.
12. **Erasure:** erase the requester of the earlier email ticket; assert the tickets, messages, objects and raw mail are gone,
    a `requester.erased` webhook arrives once, and a rerun changes nothing.
13. **Security probes:** bad inbound signature, replay, oversize body, XSS corpus message, executable attachment, public token of
    another Tenant, tracking token tamper.

## Tasks

1. Write the scripts and make them idempotent on a rerun, using a fresh Tenant per run as the other smoke scripts do.
2. Run the load tests and record numbers in `docs/helpdesk.md` as a baseline, not a target.
3. Write the Terraform for the api and worker services and the inbound adapter wiring from phase 17; keep it append-only.
4. Finish the documents; check every claim in `docs/helpdesk.md` against source or a test, and every link.
5. Update `docs/customer.md` check: the non-goal is gone and the pointer works.
6. Run the full verification below, then compare the result with the outcome contract in `plan.md` line by line.

## Verification

- `ops/smoke/helpdesk.sh` passes on a clean compose stack, twice in a row.
- `cd helpdesk && ./gradlew check` is green; `ops/smoke/isolation.sh` passes for every user; the suite smoke still passes.
- `git diff --stat` for the whole plan shows no change under `survey/`, `customer/`, `loyalty/`, `mailroom/`, `attribution/`,
  `booking/`, `workflow/` or `tenant/` Java, and under `mailer/` only the outbound attachment change of phase 11.
- Mailer's own test suite passes with no existing test changed.
- `git grep -n -E "phase|P[0-9]+-" -- helpdesk` finds no plan or phase reference in code, migrations or tests.
- Every item under "Done means" in `plan.md` has a command or a test that shows it.

## Risks

- The smoke script is long and its failures are hard to localise: each scenario prints a named pass line and the script stops at
  the first failure with the scenario name and the last response.
