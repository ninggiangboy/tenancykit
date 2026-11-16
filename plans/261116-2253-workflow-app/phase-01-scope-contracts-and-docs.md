---
phase: 1
title: "Scope, contracts and docs"
status: done
priority: P1
effort: "M"
dependencies: []
---

# Phase 1: Scope, Contracts and Docs

## Goal

The rule that makes workflow legitimate, the permission catalog and the contract folder exist before any code, so every
later phase builds against written contracts and nothing contradicts the suite rule.

## Context

The suite rule of 2026-11-16 says apps never call, listen to or name each other and the Tenant System is the only integrator.
Workflow is the Tenant side of that rule. `contracts/messaging/README.md`, `docs/suite-messaging.md` and
`docs/overview-and-scope.md` must say so in one consistent sentence. The permission file format is in
`contracts/permissions/crm.yaml` (code, description, scopes, `grantable_to_tenant_system`).

## Files to create or modify

- Create `contracts/permissions/workflow.yaml`
- Create `contracts/workflow/README.md` (the folder's index and the rules for graph, expression and vectors); the schema and
  vector files themselves arrive in phases 4 and 5
- Create `docs/workflow.md` (what is decided; sections filled by later phases)
- Modify `contracts/README.md`, `contracts/messaging/README.md`, `docs/suite-messaging.md`, `docs/overview-and-scope.md`,
  `docs/README.md` (append-only edits; re-read each file first)

## Tasks

1. Write the suite-rule paragraph once in `contracts/messaging/README.md`: workflow acts as a Tenant System, calls apps by
   their public API with a Tenant credential, receives their signed webhooks as triggers, and binds to no app's exchange.
   Link to it from the other two docs instead of repeating it.
2. Permission codes (all tenant scope, all grantable to a Tenant System, because a Tenant's own system drives workflow):
   `workflow:definition:read`, `workflow:definition:manage` (create, edit draft, publish, retire),
   `workflow:instance:read`, `workflow:instance:manage` (start, cancel, retry, replay),
   `workflow:task:read`, `workflow:task:complete`, `workflow:task:manage` (task groups), `workflow:connection:manage`, `workflow:trigger:manage`,
   `workflow:webhook:manage`. Descriptions say what the caller may do, not how.
3. `contracts/workflow/README.md` lists the three documents phases 4 and 5 produce (`graph.schema.json`,
   `expression.schema.json`, `expression-vectors/*.json`) and states the number semantics copied from Survey (decimals,
   6 fraction digits, `HALF_EVEN`) as the workflow's own contract.
4. `docs/workflow.md` records the confirmed decisions from `plan.md` (task groups, credential, definition format, core, guarantees)
   and the sample workflows. It says what does not exist yet; later phases replace those lines.

## Validation

- `git grep -n -i "workflow" -- contracts/messaging docs/suite-messaging.md docs/overview-and-scope.md` shows the same rule
  everywhere and no statement that apps may call each other.
- The permission file parses with the same loader Platform uses (`PermissionCatalogTest` of Platform picks it up in phase 2).
- No file outside `contracts/`, `docs/` and `plans/` is touched.

## Risks

- Docs and contracts drift from code: every later phase updates `docs/workflow.md` with what it built, and phase 15 checks it
  against source.
