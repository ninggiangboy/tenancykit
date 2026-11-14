---
phase: 3
title: "Keycloak workload"
status: completed
priority: P2
effort: M
dependencies: [1, 2]
---

# Phase 3: Keycloak workload

## Goal

Keycloak self-host trên ECS Fargate, lưu state ở database `keycloak` (RDS), import realm `realm-export.json`, reachable
nội bộ qua Service Connect (`keycloak.<namespace>`) và công khai qua ALB (host `auth.<domain>`).

## Findings addressed

- Compose chạy `quay.io/keycloak/keycloak:26.8.2` với `command: ["start-dev", "--import-realm"]` và volume mount
  `ops/keycloak/realm-export.json` vào `/opt/keycloak/data/import/`. `start-dev` chỉ dành cho local.
- Realm export tham chiếu secret qua placeholder env (`PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET`, `SMOKE_CLIENT_SECRET`) được
  Keycloak thay thế lúc import.
- Compose expose Keycloak trực tiếp port `8081:8080` (không qua Caddy). ECS phải expose qua ALB.
- Issuer trap áp dụng cho Keycloak: `PLATFORM_OIDC_ISSUER_URI`/`BILLING_OIDC_ISSUER_URI` là **địa chỉ công khai** người
  dùng đăng nhập; `PLATFORM_OIDC_JWK_SET_URI`/`BILLING_OIDC_JWK_SET_URI` là **địa chỉ nội bộ** server-to-server.

## Files to create / modify

- Create `ops/terraform/keycloak/task-def.tf`, `keycloak/service.tf`, `keycloak/alb.tf`.
- Create `ops/terraform/keycloak/Dockerfile` — thin image bake realm export.
- Modify `ops/terraform/outputs.tf` — export Keycloak public issuer và internal JWKS URL (local).

## Tasks

1. `Dockerfile`: `FROM quay.io/keycloak/keycloak:26.8.2` + `COPY ../../ops/keycloak/realm-export.json
   /opt/keycloak/data/import/realm-export.json`. Build/push lên ECR `keycloak` (phase 1). Ghi rõ context path trong comment
   (Terraform không build image; CI build/push, xem phase 6).
2. `task-def.tf`: keycloak container, command `["start", "--import-realm"]` (không dùng `start-dev`). Env:
   - `KC_DB=postgres`, `KC_DB_URL=jdbc:postgresql://<rds-host>:5432/keycloak`, `KC_DB_USERNAME`/`KC_DB_PASSWORD` từ Secret
     `KEYCLOAK_DB_PASSWORD` (owner-level, Keycloak tự tạo schema lúc start).
   - `KC_HOSTNAME` = public `auth.<domain>`; `KC_PROXY=edge` (ALB terminates TLS), `KC_HTTP_ENABLED=true` nếu ALB dùng HTTP
     nội bộ.
   - `KC_BOOTSTRAP_ADMIN_USERNAME`/`KC_BOOTSTRAP_ADMIN_PASSWORD` từ Secret.
   - `PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET`, `SMOKE_CLIENT_SECRET` từ Secret (để realm import thay placeholder).
   - Healthcheck `KC_HTTP_RELATIVE_PATH`/`/health/ready` hoặc curl `/health/ready`.
3. `service.tf`: `aws_ecs_service` Fargate, Service Connect name `keycloak` (FQDN `keycloak.<namespace>`), private subnet,
   target group (port 8080) + ALB listener rule host `auth.<domain>`.
4. Export hai local trong `outputs.tf` (hoặc locals): `keycloak_internal_url = http://keycloak.<namespace>:8080` và
   `keycloak_public_issuer = https://auth.<domain>/realms/billing`. Phase 4/5 dùng chúng — không hardcode.
5. Ghi comment: issuer (public) khác JWKS (internal) — đây là chỗ dễ sai nhất, cùng bẫy issuer đã có tiền lệ.

## Verification

- `terraform -chdir=ops/terraform validate`
- `grep -rn "start-dev" ops/terraform/keycloak/` → không có (production dùng `start`).
- Sau apply (sandbox): keycloak service RUNNING + healthy; `GET
  https://auth.<domain>/realms/billing/.well-known/openid-configuration` → `issuer` = `https://auth.<domain>/realms/billing`.
- `GET http://keycloak.<namespace>:8080/realms/billing/.well-known/openid-configuration` từ một task nội bộ → trả 200 (dùng
  cho JWKS).

## Success criteria

- [ ] Keycloak RUNNING + healthy trên Fargate, RDS-backed. (Cần apply để chứng minh.)
- [ ] Realm `billing` import thành công, issuer = public ALB host. (Cần apply để chứng minh.)
- [ ] Reachable nội bộ `keycloak.<namespace>:8080` và công khai `auth.<domain>`. (Cần apply để chứng minh.)
- [x] `start` production mode (không dùng dev entrypoint), proxy headers đúng (`KC_PROXY_HEADERS=xforwarded`).
- [x] Issuer public vs JWKS internal được phân biệt rõ qua local, không hardcode.

## Risks

- **Realm import placeholder thiếu env.** Thiếu một secret (ví dụ `SMOKE_CLIENT_SECRET`) làm import fail âm thầm hoặc realm
  thiếu client. Mitigation: đối chiếu placeholder trong `realm-export.json` với danh sách secret bằng `grep`.
- **`start-dev` vs `start`.** `start-dev` không dành production và tự bật H2/dev mode. Mitigation: gate grep ở Verification.
- **Keycloak tự tạo schema lúc start.** Cần database `keycloak` tồn tại (phase 2) và credential đủ quyền tạo schema. Nếu
  muốn tách owner/app cho Keycloak, thêm role riêng — nhưng Keycloak thường cần quyền rộng lúc bootstrap; ghi rõ quyết
  định.

## Security considerations

Keycloak admin + client secret trong Secrets Manager. Keycloak private subnet, chỉ ALB + Service Connect chạm tới.
`KC_HOSTNAME` + `KC_PROXY=edge` để issuer đúng public host, tránh issuer trỏ về địa chỉ nội bộ (lỗi OIDC kinh điển).

## Next steps

Phase 4 (Platform) dùng `keycloak_internal_url` cho JWKS và `keycloak_public_issuer` cho issuer.
