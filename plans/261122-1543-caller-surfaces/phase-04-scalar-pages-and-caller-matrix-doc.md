---
phase: 4
title: "Scalar pages and caller matrix doc"
status: done
priority: P1
effort: 1.5d
dependencies: [3]
---

# Phase 4: Scalar pages and caller matrix doc

## Goal
One Scalar page per caller, and `docs/audiences-and-surfaces.md` with a generated matrix that a check keeps equal to
the contracts.

## Files to Create / Modify
- Create: `ops/docs/caller.html.template` and `ops/docs/make-pages.sh` (writes `<caller>.html` for every directory
  found in `contracts/openapi/<caller>/`, and a landing `index.html` that lists the callers)
- Modify: `Makefile` (`docs` target, around line 101) to run the page generator and serve the result next to a copy
  of `contracts/openapi/`
- Delete: the service list in `ops/docs/index.html` (replaced by the generated landing page)
- Create: `ops/docs/caller-matrix.mjs` (runtime confirmed in phase 1) with `--write` and `--check`, and a `make
  docs-check` target
- Create: `docs/audiences-and-surfaces.md`: a hand-written part (the seven callers, how each authenticates, its
  container, the route rules, the exceptions, why the word is "caller") and a generated block between
  `<!-- BEGIN GENERATED -->` and `<!-- END GENERATED -->` with app × caller × prefix × container × operation count
- Modify: `docs/README.md` (index row)
- Create or modify: a CI job under `.github/workflows/` that runs `make docs-check`

## Tasks & Steps
1. Write the template: one Scalar instance whose sources are the apps present for that caller; the title names the
   caller. Pin the same `@scalar/api-reference` version used today.
2. Write the page generator; run it; open two pages and check that the source selector lists only apps that have
   operations for that caller.
3. Write the matrix script. Container rule: `customer-session`, `public`, `provider` → `<app>-public`; the others →
   `<app>-api` (`platform` and `notification-api` for those two). The prefix column comes from the actual paths.
4. Write the hand-written part of the doc, run `--write`, commit.
5. Add the CI check and prove with a deliberately drifted local edit that `--check` fails.

## Verification
- `make docs` serves seven caller pages and each loads with at least one source.
- Open one operation in a caller page: its description shows the generated "Callers: ... Requires ..." line, and the
  hand-written copies of the permission sentence are gone.
- For Booking, Customer and Loyalty (hand-written YAML) the same line is written by hand and the phase 3 comparison
  test checks it against the derivation.
- `make docs-check` exits 0; after editing one label in a contract it exits non-zero.

## Risks
- Prefixes in the matrix are all `/api/v1` until phases 5-7 land; that is expected, the matrix regenerates later.
