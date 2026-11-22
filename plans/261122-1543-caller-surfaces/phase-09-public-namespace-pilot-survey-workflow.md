---
phase: 9
title: "Public namespace pilot: Survey and Workflow"
status: pending
priority: P1
effort: 2d
dependencies: [3]
---

# Phase 9: Public namespace pilot: Survey and Workflow

## Goal
Survey's respondent surface and Workflow's signed triggers are mounted natively under `/public/<app>/...` with no
prefix strip, setting the pattern for the other apps. They still run in `*-api` until phase 12.

## Files to Create / Modify
- Modify: `backend/services/apps/survey/contexts/collection/src/adapter/java/dev/ngb/survey/collection/adapter/visit/web/PublicController.java`
  (it keeps its resource path `/p/...`; `@PublicEndpoint(caller = PUBLIC)` makes the mechanism from phase 5 serve it
  at `/public/survey/p/...`, so no app spells `/public` itself) and the security configuration permitting it
- Modify: Workflow's hook controller (`/hooks/{triggerId}` served at `/public/workflow/hooks/{triggerId}` through
  `@PublicEndpoint(caller = PROVIDER)`); remove the dual-spelling support
- Modify: `ops/caddy/Caddyfile` (`@surveyPublic path /public/survey/*` and `@workflowPublic path /public/workflow/*`,
  no `uri strip_prefix`, placed before `/survey/*` and `/workflow/*`)
- Modify: `ops/terraform/survey/alb.tf`, `ops/terraform/workflow/alb.tf` (path pattern `/public/<app>/*`; the ALB needs
  no strip because the application serves the full path)
- Modify: `SURVEY_PUBLIC_BASE_URL` default in `docker-compose.yml` and the link builder if it appends `/p`;
  `ops/seed/survey.sh`; `contracts/openapi/survey.yaml`, `workflow.yaml` and per-caller files (regenerated)
- Modify: `docs/survey.md`, `docs/workflow.md` (paths) 

## Tasks & Steps
1. Switch on the `/public/<app code>` prefix rule for these two apps; adjust security configuration; update link
   generation.
2. Move Workflow's hook controller; update the Tenant-facing docs of where triggers post.
3. Update Caddy and Terraform; update seeds and smoke tests for the new URLs.
4. Confirm the old paths answer 404 (`/survey/p/...`, `/workflow/hooks/...`, `/p/...`).

## Verification
- `./gradlew test` in Survey and Workflow; contract tests green with `x-allowed-callers: [public]` / `[provider]`.
- `make smoke`: open a survey link, submit a response, post a signed trigger.
- A search for `/survey/p/` and `/workflow/hooks` outside `/public/` returns nothing in code, contracts, ops, docs.

## Risks
- A public and an authenticated operation share a path or class: split first (stop condition).
