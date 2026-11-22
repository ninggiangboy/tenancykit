---
phase: 10
title: "Coverage gate and no-signal registry"
status: pending
priority: P2
effort: "1d"
dependencies: [7, 8, 9]
---

# Phase 10: Coverage gate and no-signal registry

## Goal

Make "mirror the event catalog" mechanically enforced across the whole suite: a single registry holds every deliberate
exclusion with its reason, and CI fails when an event type is neither mapped nor listed.

## Context

- Per-app `SignalCoverage` tests (phases 7 to 9) check an app's own mappings against its `contracts/events/<app>/`
  directory.
- Some event directories belong to services that do not produce signals: `platform` (five state-transfer events for
  replicas) and `mailer` (`email-delivery.changed`, consumed by mailroom). Mailroom and Attribution have no event
  directory.
- `contracts/events/` also holds a README; command schemas must stay out of it (`contracts/commands/README.md`).

## Requirements

1. `contracts/realtime/no-signal.yaml`: one list of `{app, event, reason}`; the per-app tests and the suite script read
   this one file (DRY).
2. A suite check script `ops/check/realtime-coverage.sh` that fails when: an event schema file exists with no mapping and
   no registry entry for an app that is expected to mirror; a registry entry names an event that no longer has a schema
   (stale); the registry lists an app that does mirror but names a mapped event (contradiction).
3. The registry covers at least: the five `platform` events (state transfer, not UI facts), the mailer event, any
   billing, booking, workflow or loyalty events excluded in phases 8 and 9, with a reason each.
4. CI: `suite.yml` runs the script; per-app workflows keep running their own coverage tests.

## Files

Create: `contracts/realtime/no-signal.yaml`, `contracts/realtime/README.md` (how to read it and add an entry),
`ops/check/realtime-coverage.sh`.

Modify: `.github/workflows/suite.yml`; per-app `SignalCoverageTest` classes to load the registry from the contracts
directory.

## Steps

1. Collect the exclusions recorded in phases 8 and 9 and the platform and mailer directories; write the registry.
2. Write the script with exit code 1 and a message naming the app and event for each violation.
3. Prove it by hand: delete one mapping in a scratch copy (or add a fake schema file) and confirm a failure; restore.
4. Wire the script into `suite.yml` and document it in the contracts README.

## Todo

- [ ] Registry with reasons
- [ ] Coverage script (three violation kinds)
- [ ] Manual failure proof recorded in the PR description
- [ ] CI step added
- [ ] Per-app tests read the shared registry

## Verification

- `ops/check/realtime-coverage.sh` exits 0 on the tree and 1 with a precise message for each of: unmapped event, stale
  entry, contradiction (demonstrated on scratch copies).
- Every per-app `./gradlew build` still passes.

## Risks

- The script and the Java tests can disagree about what "mapped" means. Mitigation: the script checks only registry
  consistency and event-directory presence; the Java tests own the mapped-or-excluded check against real mappings.

## Security

No credentials; the registry contains event names and reasons only.

## Next

Phase 13 depends on this gate for the success criteria.
