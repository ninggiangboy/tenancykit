---
phase: 1
title: "Manifests foundation"
status: pending
priority: P2
effort: S
dependencies: []
---

# Phase 1: Manifests foundation

## Goal

Dựng nền của `ops/k8s/`: namespace, ConfigMap chứa mọi biến không bí mật, Secret template chứa mọi biến bí mật, và
`.gitignore` chặn file Secret thật.

## Findings addressed

- `.env.example` liệt kê 18 giá trị, trong đó phần lớn là secret (mật khẩu DB, broker, Keycloak, master key). Không có
  cơ chế nào chuyển chúng vào Kubernetes.
- `.gitignore` hiện chặn `.env` và `*.key`/`*.pem` nhưng không có mục nào cho `ops/k8s/`.
- `docker-compose.yml` truyền biến qua `environment:`; Kubernetes cần ConfigMap + Secret tương đương.

## Files to create / modify

- Create `ops/k8s/namespace.yaml` — namespace `core-billing`.
- Create `ops/k8s/configmap.yaml` — mọi biến không bí mật.
- Create `ops/k8s/secret.example.yaml` — template, giá trị là placeholder.
- Modify `.gitignore` — thêm `ops/k8s/secret.yaml`.

## Tasks

1. `namespace.yaml`:

   ```yaml
   apiVersion: v1
   kind: Namespace
   metadata:
     name: core-billing
   ```

2. `configmap.yaml` — nhóm theo service, đặt tên key đúng như biến môi trường mà `application.yml` đọc. Bắt buộc có:

   Platform: `PLATFORM_BASE_DOMAIN`, `PLATFORM_HOSTNAME`, `PLATFORM_INTERNAL_HOSTNAMES`, `PLATFORM_TOKEN_ISSUER`,
   `PLATFORM_OIDC_ISSUER_URI`, `PLATFORM_OIDC_JWK_SET_URI`, `PLATFORM_KEYCLOAK_URL`, `PLATFORM_KEYCLOAK_REALM`,
   `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY: "false"`, `PLATFORM_SERVICE_CLIENT_ID`, `PLATFORM_EVENTS_EXCHANGE`,
   `PLATFORM_RABBITMQ_HOST`, `PLATFORM_RABBITMQ_PORT`, `PLATFORM_RABBITMQ_USER`.

   Billing dùng chung: `BILLING_BASE_DOMAIN`, `BILLING_PUBLIC_BASE_URL`, `BILLING_PLATFORM_BASE_URL`,
   `BILLING_PLATFORM_ISSUER`, `BILLING_PLATFORM_JWK_SET_URI`, `BILLING_REPLICA_CLIENT_ID`, `BILLING_OIDC_ISSUER_URI`,
   `BILLING_OIDC_JWK_SET_URI`, `BILLING_RABBITMQ_HOST`, `BILLING_RABBITMQ_PORT`, `BILLING_RABBITMQ_USER`,
   `BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS: "false"`, `BILLING_PORTAL_SECURE_COOKIE: "true"`.

   Ghi comment ngay trên ba biến dễ nhầm: `BILLING_PLATFORM_BASE_URL` là **địa chỉ gọi tới** (đổi sang FQDN nội bộ),
   còn `PLATFORM_TOKEN_ISSUER` và `BILLING_PLATFORM_ISSUER` là **chuỗi issuer** (giữ nguyên host công khai).

3. `secret.example.yaml` — `kind: Secret`, `type: Opaque`, dùng `stringData` với placeholder `<replace-me>`. Các key:
   `POSTGRES_SUPERUSER_PASSWORD`, `PLATFORM_MIGRATION_DB_PASSWORD`, `PLATFORM_DB_PASSWORD`, `BILLING_MIGRATION_DB_PASSWORD`,
   `BILLING_DB_PASSWORD`, `PLATFORM_RABBITMQ_PASSWORD`, `BILLING_RABBITMQ_PASSWORD`, `BILLING_REPLICA_CLIENT_SECRET`,
   `PLATFORM_SERVICE_CLIENT_SECRET_HASH`, `BILLING_SECRETS_MASTER_KEY`, `PLATFORM_TOKEN_SIGNING_KEY`,
   `PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET`, `PLATFORM_BOOTSTRAP_ADMIN_EMAIL`,
   `PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD`.

   Comment đầu file phải nói rõ: copy sang `secret.yaml`, điền giá trị thật, file đó bị gitignore.

4. `.gitignore`: thêm khối

   ```
   # Kubernetes secret with real values; the committed template is ops/k8s/secret.example.yaml
   ops/k8s/secret.yaml
   ```

## Verification

- `kubeconform -strict ops/k8s/namespace.yaml ops/k8s/configmap.yaml ops/k8s/secret.example.yaml`
- `grep -c "replace-me" ops/k8s/secret.example.yaml` → bằng số key trong Secret.
- `git check-ignore -v ops/k8s/secret.yaml` → khớp rule mới.
- Đối chiếu: mỗi biến trong `docker-compose.yml` phải có mặt ở ConfigMap hoặc Secret. Chạy
  `grep -oE '\b(PLATFORM|BILLING)_[A-Z0-9_]+' docker-compose.yml | sort -u` rồi so với union của hai file.

## Success criteria

- [ ] Namespace, ConfigMap, Secret template tồn tại và validate sạch.
- [ ] Không giá trị bí mật thật nào trong file được commit.
- [ ] `.gitignore` chặn `ops/k8s/secret.yaml`.
- [ ] Ba biến dễ nhầm (base URL vs issuer) có comment phân biệt ngay tại chỗ.

## Risks

Danh sách biến có thể thiếu so với `docker-compose.yml`. Mitigation: bước đối chiếu bằng `grep` ở Verification là bắt
buộc, không phải tuỳ chọn.

Đặt `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY=true` sẽ làm mỗi pod có signing key khác nhau và token bị từ chối chéo pod.
Giá trị trong ConfigMap phải là `"false"`.

## Security considerations

`secret.example.yaml` chỉ chứa placeholder. Không commit file `secret.yaml`. Không đặt secret trong ConfigMap.

`PLATFORM_SERVICE_CLIENT_SECRET_HASH` là hex SHA-256, không phải secret gốc — vẫn xếp vào Secret để tránh lộ cấu hình
xác thực.

## Next steps

Phase 2 tham chiếu ConfigMap/Secret qua `envFrom` và ghi đè những biến riêng của từng runtime.
