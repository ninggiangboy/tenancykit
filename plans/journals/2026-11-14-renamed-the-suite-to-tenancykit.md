---
title: Renamed the suite to TenancyKit
date: 2026-11-14
summary: "Renamed core-billing to tenancykit across terraform, docs, workflows and the Keycloak display name; the Java namespace dev.ngb, the billing/ service directory and appCode billing stay untouched."
---

# Renamed the suite to TenancyKit

## What happened
- Repo/suite name `core-billing` became `tenancykit`. The name was wrong because the repo already has `core/` as the shared technical core, so `core-billing` contradicted itself; Billing is the suite's first app, not its core.
- `ops/terraform/locals.tf` gained `suite_name = "tenancykit"` and a `name_prefix = "${var.environment}-${local.suite_name}"`. The declared-but-unused `name_prefix` is now the single source of every resource name; the 20 hardcoded `${var.environment}-core-billing` strings became `${var.name_prefix}`.
- `name_prefix` is passed into the `network`, `alb`, `ecs`, `mq` and `rds` modules; `suite_name` into `ecs` (ECR path) and `secrets` (secret path). Modules that still need the environment for a family name (`${var.environment}-billing-tg`, `-mq-users`, `-rds-init`) keep it.
- The Service Connect namespace default became `tenancykit.local` in `variables.tf` and both workflows.
- Docs drop the `Core` prefix from the app: `README.md`, `docs/README.md`, `docs/architecture.md`, `docs/overview-and-scope.md` and `tenant/README.md` now say **Billing**, which is what `overview-and-scope.md` already called it ("Billing is the first app").
- The OpenAPI titles became `Billing API` and `Billing API - Tenant Systems`; both contracts were regenerated with `-Dopenapi.update=true`.
- The Keycloak realm `displayName` became `TenancyKit`; `realm: "billing"` stays, because it is the identifier behind every issuer URL.

## Verification
- `ops/terraform`: `terraform init -backend=false`, `terraform fmt -check -recursive` and `terraform validate` all clean, against a temporarily downloaded CLI (none is installed).
- `billing`: `./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true` then `./gradlew build` green.
- `core`, `platform`, `tenant`: `./gradlew build` green each.
- `grep -ri "core-billing|core billing"` outside `plans/` and `.idea/` returns only `docs/system-specification.md` (the frozen baseline) and the link to the historical report `plans/reports/brainstorm-261109-2031-core-billing-contract.md`.

## Deviations
- Two `.java` files changed (`WebConfiguration`, `OpenApiContractTest`) although the plan's non-goal said no Java changes: the OpenAPI title is a product string, not a package, and the user chose to rename it.
- `terraform fmt` caught a real defect the mechanical replace left behind: `name = "${var.name_prefix}"` with no suffix, which it rewrites to `var.name_prefix`.
- Terraform has never been applied (no state file), so renaming the prefix costs no resource replacement.

## Next steps
- The local directory still says `core-billing`. Renaming it changes the docker compose project name (it follows the basename), so any `postgres-data` and `rabbitmq-data` volume would be abandoned; the only volume present is the legacy `core-billing_pgdata` from the pre-restructure compose, so nothing current is at risk.
- `dev.ngb.billing`, `dev.ngb.platform`, `dev.ngb.tenant`, the jOOQ packages, the ArchUnit rules and the `billing` appCode stay as they are, by decision.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
