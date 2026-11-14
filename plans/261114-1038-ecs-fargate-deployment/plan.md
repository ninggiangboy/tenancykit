---
title: "ECS Fargate deployment for Platform and Billing"
description: "Deploy platform, billing api, billing-worker and self-hosted Keycloak on AWS ECS Fargate via Terraform, keeping docker compose as the equal local path."
status: pending
priority: P2
effort: L
branch: dev
tags: [infra, backend, api, docs]
blockedBy: [261114-0930-service-discovery-internal-hostnames]
blocks: []
created: 2026-11-14
---

# ECS Fargate deployment cho Platform và Billing

## Outcome

`ops/terraform/` chứa toàn bộ Terraform dựng VPC, ECS cluster (Fargate), ALB, Service Connect namespace, RDS Postgres,
Amazon MQ và Keycloak self-host trên ECS. Bốn workload — `platform`, `billing` (api), `billing-worker`, `keycloak` — chạy
trên Fargate; `billing-migrate` chạy Flyway một lần trước khi api/worker start. `docker compose` vẫn là đường local song
song, không đổi hành vi. Terraform validate/plan chạy trong CI, không cần cluster AWS thật cho bước validate cú pháp.

Plan này **thay thế** `261114-0931-kubernetes-deployment` (đã chuyển hướng từ k8s sang ECS).

## Why (evidence)

Repo chưa có hạ tầng AWS nào: `ops/` chỉ có caddy, keycloak, postgres init, rabbitmq users, smoke, loadtest. Không có
Terraform/CDK/CloudFormation, không có manifest ECS.

`docker-compose.yml` đang gánh bốn vai trò mà ECS phải tái tạo bằng cách khác:

- Tạo database và role: `ops/postgres/init/00-databases.sh`.
- Tạo broker user: `ops/rabbitmq/declare-users.sh`.
- Thứ tự `billing-migrate` → `billing` → `billing-worker` qua `depends_on: service_completed_successfully`.
- Route theo Host qua `ops/caddy/Caddyfile` (path `/platform/*` và host `platform.localhost` → platform; còn lại →
  billing).

Khác với k8s, hai vai trò đầu (database/broker user) không còn là "external qua env" đơn thuần — user đã chốt dựng
RDS + Amazon MQ managed qua Terraform, và self-host Keycloak trên ECS.

## Dependencies

`blockedBy: [261114-0930-service-discovery-internal-hostnames]` — ECS Service Connect cho DNS nội bộ nhưng **không** sửa
được Host header. `TenantContextFilter` (Platform) và `SuiteTenantFilter` (Billing) lấy tenant từ
`request.getServerName()`, nên Billing gọi Platform bằng FQDN nội bộ sẽ nhận 404 nếu FQDN đó chưa nằm trong
`tenant_domains`. Plan 0930 cung cấp `PLATFORM_INTERNAL_HOSTNAMES` để khai báo FQDN nội bộ. Làm song song thì Terraform
đúng cú pháp nhưng stack không chạy.

## Non-goals

- Deploy `tenant` (sample) và `web`.
- Kubernetes manifest (plan 0931 đã bị thay thế).
- Đổi Keycloak sang Cognito hay bất kỳ IdP nào khác.
- Đổi tenant resolution khỏi Host header.
- Self-host Postgres/RabbitMQ trong ECS (đã chốt RDS + Amazon MQ managed).
- Đổi schema `tenant_domains`.
- Bỏ service `billing-migrate` khỏi `docker-compose.yml`.
- Helm/Kustomize, ECS via CDK/CloudFormation (đã chốt Terraform).

## Phases

| # | Phase | Depends on | Status |
|---|-------|-----------|--------|
| 1 | [Terraform foundation](./phase-01-terraform-foundation.md) | none | Pending |
| 2 | [Managed data: RDS and Amazon MQ](./phase-02-managed-data-rds-mq.md) | 1 | Pending |
| 3 | [Keycloak workload](./phase-03-keycloak-workload.md) | 1, 2 | Pending |
| 4 | [Platform workload](./phase-04-platform-workload.md) | 1, 2, 3 | Pending |
| 5 | [Billing workloads and migrate](./phase-05-billing-workloads-and-migrate.md) | 1, 2, 3, 4 | Pending |
| 6 | [ALB routing, validation and CI](./phase-06-alb-routing-validation-ci.md) | 3, 4, 5 | Pending |

Phase 1 dựng nền và ALB bare (listener default 503). Phase 2 dựng RDS/MQ/Secrets. Phase 3 và 4 thêm target group + ALB
rule cho Keycloak và Platform. Phase 5 thêm Billing và đổi default action của listener sang Billing. Phase 6 là cổng kiểm
chứng cuối: `terraform validate`, CI, docs, smoke.

## Cross-cutting verification

- `terraform fmt -check -recursive ops/terraform/`
- `terraform -chdir=ops/terraform validate` (sau `terraform init`, không cần AWS creds cho validate)
- `cd platform && ./gradlew build`
- `cd billing && ./gradlew build`
- `docker compose up -d --build --wait && ops/smoke/isolation.sh && ops/smoke/suite-smoke.sh`
- `git diff docker-compose.yml` rỗng (plan này không sửa compose)
- `git diff --stat` chỉ chứa `ops/terraform/`, `.gitignore`, `.github/workflows/`, `docs/`, `README.md`

## Global risks

- **Hai đường deploy trôi khỏi nhau.** Mỗi biến môi trường mới phải xuất hiện ở cả `docker-compose.yml` lẫn Terraform
  (locals/variables). Mitigation: phase 6 thêm gate `grep` đối chiếu danh sách biến hai bên.
- **Thứ tự migrate không có tương đương `depends_on`.** ECS không có cross-service ordering. Mitigation: one-shot task
  chạy trong pipeline trước khi update-service; Flyway versioned nên re-run an toàn. Phase 5 nêu rõ.
- **Issuer bị đổi nhầm sang DNS nội bộ.** Bẫy đã có tiền lệ (journal `2026-11-14-fix-issuer-fallback-traps`). Áp dụng
  cho cả Platform/Billing lẫn Keycloak: issuer = địa chỉ công khai, JWKS = địa chỉ nội bộ. Phase 4, 5 liệt kê rõ.
- **RDS không tự chạy init script.** Compose dùng `00-databases.sh`; RDS không có tương đương tự động. Mitigation: one-shot
  init task hoặc provider `postgresql`. Phase 2 nêu rõ và chọn một cách.
- **Secret thật lọt vào git.** Mitigation: Secrets Manager giữ giá trị; `.tf` chỉ reference ARN; `.gitignore` chặn
  `*.tfvars`, `*.tfstate*`, `.terraform/`.
- **Terraform validate chỉ bắt lỗi cú pháp.** Không chứng minh được stack chạy. Khoảng trống đã biết và đã chấp nhận;
  ghi vào Risks phase 6. Chứng minh end-to-end cần AWS sandbox thật.

## Unresolved questions

- **Domain công khai thật** (dùng cho ALB host và các biến issuer) chưa biết. Terraform dùng variable placeholder rõ ràng.
- **Tên Service Connect namespace** chưa chốt. Dùng `core-billing.local` làm placeholder; FQDN nội bộ có dạng
  `platform.core-billing.local`. Phải là một nguồn sự thật duy nhất (Terraform local), không hardcode rải rác.
- **Môi trường:** giả định **một env (dev) trước**; Terraform parameterize bằng `tfvars` + workspace để thêm staging/prod
  sau là thêm file, không viết lại.
- **Backend state Terraform:** S3 + DynamoDB lock (khuyến nghị) hay local. Chọn trong phase 1.
- **Region/AZ:** giả định có thể cấu hình qua variable, mặc định gợi ý `us-west-2` (AWS Emulator chỉ verify ở đó, nhưng
  đây là deploy thật nên chọn region theo nhu cầu thật).
