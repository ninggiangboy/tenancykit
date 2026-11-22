---
phase: 12
title: "Public profile, core and architecture rules"
status: pending
priority: P1
effort: 2d
dependencies: [11]
---

# Phase 12: Public profile, core and architecture rules

## Goal
A `public` runtime profile exists beside `api`, `worker` and `migrate`; architecture tests fail when a controller sits
in the wrong profile; Survey and Workflow run as `*-public` containers as the pilot.

## Files to Create / Modify
- Create: a composite annotation in `core-kernel` (`@PublicSurface`, meaning `@Profile("public")`) for controllers
  whose callers are `customer-session`, `public` or `provider`
- Modify: `backend/libs/core/core-arch/src/test/java/dev/ngb/core/arch/ArchitectureRulesTest.java` (shared rules: a
  controller carrying `@PublicSurface` has no permission-protected method; a permission-protected controller is
  `@Profile("api")`; both directions) and each app's `ArchitectureTest`
- Modify: each pilot app's `application.yml` (default profiles now `api` + `worker` + `public` for a run with no
  profile) and a new `application-public.yml` guarded by `public & !api & !worker`
- Modify: Survey and Workflow security configuration so the `public` runtime permits `/public/<app>/**` and actuator,
  and does not start the resource server for authenticated callers or any job
- Modify: `docker-compose.yml` (`survey-public`, `workflow-public`: the same image with the public profile),
  `ops/caddy/Caddyfile` (`/public/survey/*` upstream `{$SURVEY_PUBLIC_UPSTREAM:survey-public:8080}`; same for Workflow)
- Modify: `ops/terraform/{survey,workflow}/services.tf`, `task-defs.tf`, `alb.tf` (a public target group and service;
  the public ALB rule points to it)
- Modify: `docs/runtime-profiles.md` (the fourth profile and the process table)

## Tasks & Steps
1. Add `@PublicSurface` and the shared architecture rules; make Survey's and Workflow's pass.
2. Add the `public` profile to the pilot apps; list the beans and secrets the public runtime needs (DB, replica
   tables, link or trigger secrets, rate limiter, broker publisher where used) and confirm nothing else starts.
3. Tests with the public profile: `/public/survey/...` 200, `/api/v1/...` 404; with `api`: the reverse.
4. Compose, Caddy and Terraform for the two pilots; `terraform validate`.

## Verification
- `./gradlew test` in `core-arch` and the two pilot services (the new architecture rules included).
- `docker compose up -d --wait`; `docker compose ps` shows `survey-public` and `workflow-public` healthy; the four
  route checks (public path to api container 404, authenticated path to public container 404, and the two positives).
- `terraform validate` passes.

## Risks
- `*-public` needs the same secrets as `*-api`; copy the existing secret references, never inline values.
- A job or listener starts in the public runtime: the profile guard test (like the existing api/worker one) catches it.
