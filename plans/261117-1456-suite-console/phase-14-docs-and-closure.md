---
title: "Phase 14: Docs and closure"
status: done
priority: P2
effort: "1d"
dependencies: [13]
---

# Phase 14: Docs and closure

## Goal

The documentation matches what shipped, and the plan is closed against its outcome contract.

## Docs impact (read each file before editing, verify claims against the code afterwards)

| File | Change |
|---|---|
| `README.md` (line listing `frontend/`) | `frontend/` is the Suite Console monorepo, not a placeholder |
| `docs/architecture.md` (rows "Frontend" and "Repository layout") | Record two shells, remotes, the registry, the `/console/` prefix and that the Frontend row is now delivered; keep the TanStack decision |
| `docs/overview-and-scope.md` (non-goal at the Suite Console line) | Console is delivered for Platform and Billing; the other apps' UIs stay out |
| `docs/platform-service.md` | UI remote registry, `frontend-ci` service client and its rotation, entitlement read, Keycloak redirect sync and its scale limit |
| `docs/authentication-and-authorization.md` | Console login with `suite-console`, in-memory tokens, redirect URIs per tenant domain |
| `contracts/README.md` and `contracts/permissions/platform.yaml` | Permission codes added in phase 3; no OpenAPI path change needed beyond the regenerated document |
| New `docs/console.md` | How the console works: the design system (artifact link, pinned version, how `@suite/ui` ports it, how to sync it), layout, registry flow, deploy script, adding a remote (the extension path), running and testing, limits; under the 800-line cap |
| `docs/README.md` | Index entry for `docs/console.md` |
| `plans/261112-0726-enterprise-suite-foundation/plan.md` | Point the "Suite Console follow-up" note at this plan |

## Steps

1. Read each file above and update only the rows that changed. Search `README.md`, `docs/`, `ops/`, `docker-compose.yml`
   and `.github/` for the old `web/` directory name and fix each hit; leave finished plans under `plans/` as written
   (they are records), including the ECS deployment plan's mention of `web`.
2. Check every link and every command in the new and edited docs by running or opening it.
3. Compare the shipped result with the outcome contract in `plan.md`; tick every Done-means item with its evidence
   (the E2E report and the Gradle and pnpm runs).
4. Close the plan (`ak plan` close operation, see its live help) and write the journal entry (`ak-journal`) unless
   skipped by preference.
5. Update the project memory entry `suite-console-decisions` with what was built and any decision the phases changed.

## Todo

- [x] README, architecture, overview, platform-service, authentication docs
- [x] Contracts README and permission catalog note
- [x] `docs/console.md` and the docs index
- [x] Links and commands verified
- [x] Done-means checklist closed with evidence (in `plan.md`)
- [x] Plan closed, journal written, memory updated

## Verification

```bash
cd frontend && pnpm turbo run lint typecheck test build && cd ../platform && ./gradlew build
```

plus a link check of the edited docs. Both builds exit 0.

## Risks

Docs drift if a phase changed a decision and the docs describe the plan, not the result; describe only what the code and
tests show.

## Security

The docs name the `frontend-ci` secret's location and rotation, never its value.
