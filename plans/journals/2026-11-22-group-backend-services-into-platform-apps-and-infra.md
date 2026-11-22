---
title: "Group backend services into platform, apps and infra"
date: 2026-11-22
summary: "Moved 8 services to backend/services/apps and 2 to backend/services/infra, fixed Gradle and path references, verified builds."
---

# Group backend services into platform, apps and infra

## What happened
`backend/services/` held 11 sibling builds of three different kinds. They are now grouped:
`platform/` stays in place, `apps/` holds billing, booking, customer, loyalty, survey, attribution, workflow and
mailroom, and `infra/` holds mailer and notification. The move used `git mv` on whole directories, so history is kept.

Fixed along the way:
- `includeBuild("../../libs/core")` became `../../../libs/core` in the 10 moved builds.
- The `../../../../../contracts/...` paths in each `apps/app-boot/build.gradle.kts` gained one `../`.
- About 220 path references in 12 CI workflows, `docker-compose.yml`, Dockerfiles, `ops/` scripts, `README.md`,
  `contracts/` and `docs/` now point at the new locations. `plans/` was left alone as historical record.
- `ops/terraform/README.md` built Dockerfile paths from `services/$app`; it now matches the group with a glob.
- Removed the untracked, empty `backend/samples/` left over from the sample tenant removal.
- Corrected a mistake in my own docs: the first draft said "has a permission catalog means app", but `notification`
  has one and lives in `infra/`. The README and architecture row now say apps are Tenant-used products and infra
  serves the suite itself.

## Verification
- `./gradlew build` with tests passed for all 11 builds from the new locations.
- `docker compose config` is valid; `run-service.sh` resolves all 11 names; every Dockerfile path referenced by
  compose, CI and ops exists.
- Docker images built for `apps/billing` and `infra/mailer`.
- `git grep` for the old paths outside `plans/` returns 0; no Java file changed content.

## Unexplained
- `booking` and `customer` failed once in the first full run (after 17s and 5s) and passed on rerun, including a
  `cleanTest build`. I never saw the error text, so the cause is unknown; a transient infrastructure problem is a
  guess, not a finding.
- Empty `<service>/build-logic/bin/main` trees reappeared at the old locations for 7 services. They are gitignored
  and held no files; I removed them and builds did not recreate them. An IDE or language server is the likely
  source, unverified.

## Next steps
- Nothing is committed; the work is staged on `refactor/group-services-by-kind`.
- Watch the first CI run on the branch, since workflow path filters were only checked statically.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
