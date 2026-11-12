---
phase: 5
title: "Docs"
status: done
priority: P3
effort: "0.5d"
dependencies: [1, 3, 4]
---

# Phase 5: Docs

## Goal

The lock order and the precondition rule are written where maintainers look before adding a flow.

## Files to modify

- `docs/concurrency-and-locking.md`:
  - Add the Platform rows from phase 1 and the Billing customer and webhook endpoint rows to "Observed order".
  - Add a rule: a write that bumps a state-transfer `version` holds the row lock across read, bump and write, and the
    repository `update` refuses a non-increasing version. State why: the replica drops an equal version.
  - Add a "Conditional updates" subsection:
    - the ETag format;
    - `If-Match` is optional, strong comparison, 412 versus 409;
    - the check runs after the lock and before any mutation, so a no-op change is never answered with 200 on a stale
      tag;
    - a version may move without a visible field changing.
- `docs/security-and-reliability.md`: add one line under reliability linking to the new subsection. Edit only if the
  file already has a matching list; otherwise skip it.
- `README.md`: no change. The regenerate commands are already documented.

## Verification

- Every file path and class name cited in the edited docs exists: `git grep` each one.
- `docs.maxLoc` (800) still holds for each edited file.
