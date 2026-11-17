---
phase: 10
title: "Control flow: branch, parallel, for-each, sub-workflow"
status: done
priority: P1
effort: "L"
dependencies: [7, 8, 9]
---

# Phase 10: Control Flow

## Goal

Workflows can branch on conditions, run branches in parallel and join them, loop over a list, and call another workflow, all
inside enforced limits.

## Context

Tokens already carry `parent_token`, `branch_key` and `iteration` (phase 6). Limits come from the Tenant's quota settings
(phase 14 owns the values; defaults here): steps per instance 1,000, parallel branches 50, for-each items 1,000, sub-workflow
depth 5.

## Files to modify or create

- `workflow/contexts/runtime`: `BranchExecutor`, `ParallelExecutor`, `JoinExecutor`, `ForEachExecutor`, `SubflowExecutor`
- Migration `V7__control_flow.sql`: `join_state` (parent token, expected, arrived, outputs), `instance.parent_instance_id`,
  `instance.parent_token_id`
- Tests

## Tasks

1. `branch`: ordered cases of `{if: expression, next}` plus a mandatory `else`; the first true case wins; the decision is
   recorded in the step output.
2. `parallel` creates one child token per branch; `join` waits for all (`all`), the first (`any`), or a count (`n_of`); the
   join's arrival is one conditional update on `join_state`, so concurrent arrivals never lose a count or fire twice. Outputs of
   branches are collected by branch name.
3. `foreach` over a list expression runs the body per item with `{item}` and `{index}`, either sequentially or with a
   concurrency limit; it collects per-item outputs; an item failure follows the node's `on_error` policy (stop, or continue and
   collect errors).
4. `subflow` starts a child instance of a published definition (pinned at publish: by code and version, or `latest` resolved
   at start and then pinned), waits for it, and returns its output; cancelling the parent cancels the child.
5. Enforce every limit at run time with a stable failure reason (`limit_exceeded:<name>`) that fails the instance instead of
   stalling; publish rejects what it can see statically (cycles, oversized branches).

## Validation

- Tests: 50 concurrent branch arrivals join once; `any` ignores late arrivals; `n_of` fires at n; for-each over 1,000 items with
  concurrency 10 finishes with 1,000 `step_run`s and no duplicates; a sub-workflow cycle is refused at publish and, if built
  dynamically, at depth 6.
- The erasure sample's fan-out (phase 15) runs with the join collecting four outputs.

## Risks

- Fan-out multiplies load: limits plus the per-Tenant concurrency cap in phase 14.
