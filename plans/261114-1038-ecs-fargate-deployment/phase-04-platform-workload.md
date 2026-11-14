---
phase: 4
title: "Platform workload"
status: pending
priority: P2
effort: M
dependencies: [1, 2, 3]
---

# Phase 4: Platform workload

## Goal

Platform chạy trên Fargate, Service Connect nội bộ (`platform.<namespace>`) cho Billing gọi, ALB rule cho admin host +
path `/platform/*`, và `PLATFORM_INTERNAL_HOSTNAMES` khai báo đúng FQDN nội bộ.

## Findings addressed

- Compose chạy Platform qua service `platform` (port `8082:8080`), healthcheck `/actuator/health/readiness`, và network
  alias `platform.localhost` để Host khớp domain đã đăng ký. Trên ECS không có network alias — Service Connect thay thế.
- `PlatformBootstrap` đăng ký hostname từ `PLATFORM_HOSTNAME` + `PLATFORM_INTERNAL_HOSTNAMES` (sau khi plan 0930 land).
- Platform migrate lúc start (chạy Flyway bằng owner role) — xem Risks về multi-replica.
- `PLATFORM_OIDC_ISSUER_URI` (public) vs `PLATFORM_OIDC_JWK_SET_URI` (internal `keycloak.<namespace>`).

## Files to create / modify

- Create `ops/terraform/platform/task-def.tf`, `platform/service.tf`, `platform/alb.tf`.

## Tasks

1. `task-def.tf`: image ECR `platform`, port 8080, `cpu`/`memory` theo tinh thần compose (`mem_limit: 512m` → memory 512,
   cpu 512). Healthcheck `GET /actuator/health/readiness` (hoặc ECS healthCheck `CMD` tương đương, ghi lựa chọn). Env:
   - `PLATFORM_DB_URL`/`PLATFORM_DB_USER`/`PLATFORM_DB_PASSWORD` (owner role, vì Platform migrate lúc start) và
     `PLATFORM_MIGRATION_DB_USER`/`PLATFORM_MIGRATION_DB_PASSWORD` từ Secret.
   - `PLATFORM_RABBITMQ_HOST` = Amazon MQ endpoint, `PLATFORM_RABBITMQ_USER`/`PASSWORD` từ Secret.
   - `PLATFORM_KEYCLOAK_URL` = internal `keycloak.<namespace>:8080`; `PLATFORM_KEYCLOAK_REALM` = `billing`.
   - `PLATFORM_OIDC_ISSUER_URI` = public `https://auth.<domain>/realms/billing` (dùng local từ phase 3).
   - `PLATFORM_OIDC_JWK_SET_URI` = `http://keycloak.<namespace>:8080/realms/billing/protocol/openid-connect/certs`.
   - `PLATFORM_HOSTNAME` = public admin host; `PLATFORM_INTERNAL_HOSTNAMES` = `platform.<namespace>` (local `internal_fqdn`).
   - `PLATFORM_TOKEN_ISSUER` = public host (giữ nguyên, không đổi sang DNS nội bộ).
   - `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY` = `false` (multi-replica: signing key phải ổn định qua `PLATFORM_TOKEN_SIGNING_KEY`
     từ Secret).
   - `PLATFORM_SERVICE_CLIENT_ID` = `billing`, `PLATFORM_SERVICE_CLIENT_SECRET_HASH` từ Secret.
2. `service.tf`: `aws_ecs_service` Fargate, Service Connect name `platform`, `desired_count: 2`, private subnet, target
   group (8080) + ALB listener rules: (a) path `/platform/*` (strip không cần — Platform nhận path gốc sau ALB; ghi rõ
   ALB không rewrite Host), (b) host `platform.<domain>`. Ghi comment: `PLATFORM_INTERNAL_HOSTNAMES` phải chứa
   `platform.<namespace>`, nếu không Billing gọi nội bộ nhận 404 (plan 0930).
3. Giữ nguyên Host header qua ALB (ALB forward Host mặc định). Không rewrite Host thành tên upstream — điều này phá tenant
   resolution.

## Verification

- `terraform -chdir=ops/terraform validate`
- `grep -rn "PLATFORM_INTERNAL_HOSTNAMES\|internal_fqdn" ops/terraform/platform/` → đúng FQDN `platform.core-billing.local`.
- `grep -rn "PLATFORM_TOKEN_ISSUER\|PLATFORM_OIDC_ISSUER_URI" ops/terraform/platform/` → giá trị chứa host công khai, không
  chứa `core-billing.local`.
- `grep -rn "PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY" ops/terraform/platform/` → `false`.

## Success criteria

- [ ] Platform RUNNING + healthy trên Fargate (2 replica).
- [ ] Service Connect name `platform.<namespace>`.
- [ ] `PLATFORM_INTERNAL_HOSTNAMES` đúng FQDN nội bộ.
- [ ] Issuer (public) và JWKS (internal) đúng; `PLATFORM_TOKEN_ISSUER` giữ public.
- [ ] `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY=false` và signing key từ Secret.
- [ ] ALB không rewrite Host header.

## Risks

- **Issuer trap.** Đổi `PLATFORM_TOKEN_ISSUER`/`PLATFORM_OIDC_ISSUER_URI` sang DNS nội bộ làm mọi token bị từ chối (journal
  `2026-11-14-fix-issuer-fallback-traps`). Mitigation: gate grep ở Verification; comment tại chỗ.
- **Platform migrate lúc start với 2 replica.** Hai task cùng chạy Flyway có thể tranh nhau; Flyway có lock nên một task
  chờ, readiness trễ. Mitigation: như k8s plan — nếu quan sát thấy tranh chấp, chuyển sang Job migrate riêng cho Platform;
  ghi ghi chú. `minimumHealthyPercent`/`maximumPercent` hợp lý cho rolling.
- **Ephemeral key `true`.** Mỗi task có signing key khác nhau, token bị từ chối chéo task. Mitigation: buộc `false` +
  `PLATFORM_TOKEN_SIGNING_KEY` cố định từ Secret.

## Security considerations

`runAsNonRoot`, `readOnlyRootFilesystem` nếu Dockerfile không cần ghi đĩa. Secret qua Secrets Manager, không plaintext env.
Chỉ hostname khai báo tường minh được đăng ký (bề mặt tấn công hữu hạn từ plan 0930).

## Next steps

Phase 5 dùng `platform.<namespace>` làm `BILLING_PLATFORM_BASE_URL` và `BILLING_PLATFORM_JWK_SET_URI`.
