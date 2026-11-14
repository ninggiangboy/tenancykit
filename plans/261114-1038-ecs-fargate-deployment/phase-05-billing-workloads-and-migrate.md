---
phase: 5
title: "Billing workloads and migrate"
status: pending
priority: P2
effort: L
dependencies: [1, 2, 3, 4]
---

# Phase 5: Billing workloads and migrate

## Goal

Billing chạy đủ ba hình thái trên ECS — migrate một lần, api, worker — thứ tự khởi động đúng, và gọi Platform qua
Service Connect FQDN nội bộ.

## Findings addressed

- `docker-compose.yml` dùng `depends_on: service_completed_successfully` để bảo đảm `billing-migrate` xong trước khi
  `billing`/`billing-worker` start. ECS không có cross-service ordering.
- `billing-migrate` chạy `SPRING_PROFILES_ACTIVE=migrate` (Flyway on, web/job off), `billing` chạy `api`, `billing-worker`
  chạy `worker`. Cả ba dùng chung image `billing/apps/app-boot/Dockerfile`.
- `application-worker.yml` tắt virtual threads; api bật virtual threads. Manifest/Terraform không được vô tình ghi đè.
- Issuer trap: `BILLING_PLATFORM_BASE_URL`/`BILLING_PLATFORM_JWK_SET_URI` đổi sang DNS nội bộ; `BILLING_PLATFORM_ISSUER`
  giữ nguyên host công khai.

## Files to create / modify

- Create `ops/terraform/billing/task-defs.tf`, `billing/services.tf`, `billing/migrate.tf`.

## Tasks

1. `task-defs.tf`:
   - `billing-migrate`: image ECR `billing`, `SPRING_PROFILES_ACTIVE=migrate`, env dùng chung với api (xem `*billing-environment`
     trong compose). Flyway on bằng owner role (`BILLING_MIGRATION_DB_USER`/`PASSWORD`).
   - `billing` (api): `SPRING_PROFILES_ACTIVE=api`, Service Connect name `billing`, `desired_count: 2`, port 8080.
   - `billing-worker`: `SPRING_PROFILES_ACTIVE=worker`, `desired_count: 2`, không cần target group (không serve HTTP công
     khai), nhưng giữ healthcheck actuator.
2. `migrate.tf`: migrate là **one-shot task**, không phải `aws_ecs_service`. Chạy trong pipeline (phase 6): `aws ecs
   run-task` với task def migrate → poll tới `STOPPED` + exit code 0 → rồi `aws ecs update-service --force-new-deployment`
   cho api và worker. Flyway versioned nên re-run an toàn.
3. **Ba biến phải đúng, đây là chỗ dễ sai nhất:**
   - `BILLING_PLATFORM_BASE_URL=http://platform.<namespace>:8080` — **đổi** sang DNS nội bộ.
   - `BILLING_PLATFORM_JWK_SET_URI=http://platform.<namespace>:8080/.well-known/jwks.json` — **đổi**.
   - `BILLING_PLATFORM_ISSUER` = public host — **giữ nguyên**; đổi làm mọi suite token bị từ chối.
4. `BILLING_OIDC_ISSUER_URI` = public Keycloak issuer; `BILLING_OIDC_JWK_SET_URI` = internal `keycloak.<namespace>`.
   `BILLING_BASE_DOMAIN` = public base domain; `BILLING_PUBLIC_BASE_URL` = public ALB URL.
5. `BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS=false` (compose đặt `true` cho local), `BILLING_PORTAL_SECURE_COOKIE=true`.
6. Worker không bật virtual threads: không đặt `spring.threads.virtual.enabled` cho worker.
7. Ghi comment mapping: `billing-migrate` dùng owner role (Flyway), api/worker dùng app role (Flyway off) — giữ đúng mô
   hình bảo mật hiện tại.

## Verification

- `terraform -chdir=ops/terraform validate`
- `grep -rn "SPRING_PROFILES_ACTIVE" ops/terraform/billing/` → đúng ba giá trị `migrate`, `api`, `worker`.
- `grep -rn "BILLING_PLATFORM_BASE_URL" ops/terraform/billing/` → chứa `platform.core-billing.local`, không chứa IP.
- `grep -rn "PLATFORM_ISSUER\|TOKEN_ISSUER" ops/terraform/` → không có `core-billing.local` trong giá trị issuer.
- `grep -rn "spring.threads.virtual" ops/terraform/billing/` → không có cho worker.
- `grep -rn "WEBHOOKS_ALLOW_INSECURE_TARGETS\|PORTAL_SECURE_COOKIE" ops/terraform/billing/` → `false` / `true`.

## Success criteria

- [ ] migrate one-shot task, api + worker service riêng, profile đúng.
- [ ] Migrate chạy trước api/worker (pipeline), Flyway re-run an toàn.
- [ ] `BILLING_PLATFORM_BASE_URL`/`BILLING_PLATFORM_JWK_SET_URI` trỏ DNS nội bộ; issuer giữ public.
- [ ] `BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS=false`, `BILLING_PORTAL_SECURE_COOKIE=true`.
- [ ] Worker không bật virtual threads.
- [ ] `terraform validate` sạch.

## Risks

- **Thứ tự migrate.** Không có `depends_on` trong ECS. Mitigation: one-shot task trong pipeline; Flyway idempotent. Không
  dùng `dependsOn` container trong cùng task (ép migrate/api/worker chung task, phá vỡ mô hình owner/app).
- **Hai worker cùng chạy job.** Job đã multi-worker safe (`for update skip locked`, `job_leases`, `job_runs`), nên 2
  replica an toàn về logic. Xác nhận lại khi có sandbox thật.
- **Hai api cùng rebuild replica.** `ReplicaBootstrap` chạy ở profile worker (`ReplicaConfiguration` `@Profile("worker")`),
  nên api không rebuild. Xem lại khi có sandbox.
- **Migrate re-run mỗi deploy.** Flyway versioned nên không ghi lại schema; nhưng cần đảm bảo migrate task không chạy song
  song với chính nó (pipeline serialize).

## Security considerations

`BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS` phải `false` trên cluster (SSRF). Secret qua Secrets Manager, không plaintext.
Owner/app role tách biệt giữ nguyên. Worker không serve HTTP công khai — không gắn target group công khai.

## Next steps

Phase 6 đổi ALB listener default action sang Billing target group, thêm CI deploy (migrate → deploy), docs và smoke.
