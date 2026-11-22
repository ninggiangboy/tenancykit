---
phase: 1
title: "Baseline and preconditions"
status: pending
priority: P1
effort: "0.5d"
dependencies: []
---

# Phase 1: Baseline and preconditions

## Goal

Start from a committed, green tree, so the new service and the producer changes are reviewable on their own and are not
tangled with the service moves still pending on this branch.

## Context

At the start of the planning session the branch `refactor/group-services-by-kind` held many uncommitted renames
(`backend/services/<app>` to `backend/services/apps/<app>`, `infra/mailer`, `infra/notification`) and edited workflows
and README. Every path in this plan assumes that layout.

## Requirements

- The refactor is committed or merged, so `git status` shows no pending rename.
- The workflows under `.github/workflows/` point at the moved directories.
- The baseline builds and tests pass before any change, so later failures are attributable.

## Files

- Read only: `.github/workflows/*.yml`, `README.md`, `docker-compose.yml`, `Makefile`.
- No source changes in this phase.

## Steps

1. Run `git status` and `git log -5`. If renames are still uncommitted, stop and ask the user to commit or merge that
   work (do not commit it on their behalf unless asked).
2. Create the working branch from the committed base (for example `feat/suite-realtime-signals`).
3. Build the shared core and the two services that most resemble the new one:
   `cd backend/libs/core && ./gradlew build`, `cd backend/services/infra/notification && ./gradlew build`,
   `cd backend/services/infra/mailer && ./gradlew build`.
4. Build and test the frontend: from `frontend/`, `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm typecheck`,
   `pnpm test`.
5. Record the baseline (pass or the list of already failing tests) in a short report under
   `plans/reports/` so later regressions are not blamed on this work.

## Todo

- [ ] Confirm a clean tree on a committed base
- [ ] Create the working branch
- [ ] Core, notification, mailer builds green (or failures recorded)
- [ ] Frontend lint, typecheck, test green (or failures recorded)
- [ ] Baseline report written

## Verification

- `git status --short` prints nothing.
- The four build commands above exit 0, or each failure is listed in the baseline report with its cause.

## Risks

- Builds need Docker for Testcontainers; if Docker is down, tests fail for an unrelated reason. Start Docker first.
- A pre-existing failing test would otherwise be mistaken for a regression of this work.

## Security

No credentials are read or written in this phase.

## Next

Phase 2.
