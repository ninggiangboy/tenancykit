---
phase: 8
title: "Internal routes edge block"
status: pending
priority: P1
effort: 1d
dependencies: [7]
---

# Phase 8: Internal routes edge block

## Goal
`/internal/**` is answered 404 by Caddy and by the ALB, and replica rebuild and the replica listener keep working in
compose and in ECS.

## Files to Create / Modify
- Modify: `ops/caddy/Caddyfile` (a `handle` for `/internal/*` and `/*/internal/*` placed before every per-app handle,
  answering 404)
- Modify: `ops/terraform/alb/alb.tf` (a listener rule at a priority below every app rule, matching `*/internal/*`,
  `fixed_response` 404)
- Modify only if phase 1 shows apps reach Platform through the edge in compose: `docker-compose.yml` (`*_PLATFORM_BASE_URL`
  to Platform's own address, and `PLATFORM_INTERNAL_HOSTNAMES` so Platform still resolves the tenant from Host)
- Modify: `ops/terraform/README.md` (the rule and why `/internal` is never routed)

## Tasks & Steps
1. If needed, change compose so server-to-server calls no longer pass the edge; verify a replica rebuild first.
2. Add the Caddy rule and the ALB rule.
3. Prove it: `curl -i http://localhost:8080/platform/internal/v1/replica/tenants` and the same through
   `platform.localhost` answer 404; a tenant host path `/survey/internal/v1/x` answers 404.
4. Prove replica rebuild still works: restart a worker with an empty replica and watch it populate.
5. `terraform validate` in `ops/terraform`.

## Verification
- The three curl checks above return 404; `make smoke` passes; `terraform validate` passes.
- ECS: confirmed from `locals.tf` and the task definitions that apps use the internal FQDN, so the ALB rule cannot
  reach them.

## Risks
- Blocking breaks rebuild in any environment: stop and ask (stop condition).
