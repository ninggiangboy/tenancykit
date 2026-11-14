---
title: "Dựng ECS Fargate cho Platform, Billing và Keycloak bằng Terraform"
date: 2026-11-14
summary: "Land toàn bộ ops/terraform: VPC, ECS Fargate, RDS, Amazon MQ, Keycloak, ALB, migrate pipeline và CI/docs; validate sạch nhưng chưa chạy sandbox AWS."
---

# Dựng ECS Fargate cho Platform, Billing và Keycloak bằng Terraform

## What happened

Plan `261114-1038-ecs-fargate-deployment` được implement đầy đủ trong `ops/terraform/`:

- Nền tảng: VPC 2 AZ (public/private, một NAT cho dev), security group tách ALB/task/data, ECS cluster Fargate, 3 ECR repo
  (`platform`, `billing`, `keycloak`), CloudWatch log group, execution role đọc Secrets Manager, Cloud Map namespace.
- Dữ liệu managed: RDS Postgres với 4 database (platform, billing, tenant, keycloak) + owner/app role; Amazon MQ RabbitMQ
  private với user admin/platform/billing; toàn bộ password sinh ngẫu nhiên trong Secrets Manager.
- Workload: Keycloak dùng `start --import-realm`, Platform 2 replica migrate-on-start, Billing api/worker 2 replica, và
  `billing-migrate` là task definition one-shot chạy trước khi roll service. Service Connect tên `keycloak`, `platform`,
  `billing`.
- ALB: priority 10 path `/platform/*`, 20 host `platform.<domain>`, 30 host `auth.<domain>`, default forward Billing; Host
  được giữ nguyên.
- CI: `terraform.yml` (fmt/init -backend=false/validate, plan khi có OIDC role) và `deploy.yml` (apply → build/push 3 image
  → init một lần → migrate → roll service).
- Docs: `ops/terraform/README.md`, mục ECS trong `docs/runtime-profiles.md`, pointer trong `README.md`.

## Decisions

- Backend giữ `local` để `validate` không cần AWS; khối S3 + DynamoDB nằm trong comment `main.tf` và README, CI deploy giả
  định backend chia sẻ.
- Service Connect chỉ nhận Cloud Map HTTP namespace, nên dùng `aws_service_discovery_http_namespace` thay cho private DNS
  namespace mà plan ghi; dạng FQDN `<service>.<namespace>` không đổi.
- Target group của Billing nằm trong module `alb`, không phải module `billing`: default action của listener phải tham chiếu
  TG, đặt TG ở module billing sẽ tạo vòng đồ thị module.
- RDS init dùng script riêng `rds/init-databases.sh`, không chạy nguyên `ops/postgres/init/00-databases.sh`: RDS master
  không được `alter default privileges for role <khác>`, nên mỗi owner tự set default privileges.
- Platform/Billing dùng cpu 512 + memory 1024 vì Fargate không cho 512 CPU với 512 MiB.
- Billing api/worker đặt `ignore_changes = [task_definition]` để `terraform apply` không roll trước migrate; deploy
  workflow update service tường minh sau khi migrate exit 0.
- ALB không strip prefix: rule `/platform/*` forward nguyên path, host admin `platform.<domain>` là đường tương đương
  compose. Đã ghi rõ trong README và phase 6.

## Verification

- `terraform fmt -check -recursive ops/terraform/` sạch.
- `terraform -chdir=ops/terraform init -backend=false` + `validate` sạch (AWS provider 5.100.0, random 3.9.1, tls 4.4.2).
- Grep gates: không còn `start-dev`, không còn default 503, ba profile migrate/api/worker đúng, issuer không chứa
  `core-billing.local`, worker không bật virtual threads, `BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS=false`,
  `BILLING_PORTAL_SECURE_COOKIE=true`, `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY=false`.
- `git diff docker-compose.yml` rỗng; FQDN nội bộ chỉ xuất hiện trong `locals.tf`.

## Next steps

- Plan `261114-0930` chưa land: `PLATFORM_INTERNAL_HOSTNAMES` chưa có trong code Platform, nên lời gọi nội bộ từ Billing
  sẽ 404 cho tới khi plan đó xong.
- Cần AWS sandbox để chứng minh: task khởi động, realm import, RDS/MQ init task, migrate exit 0, ALB routing.
- `suite-smoke.sh` hiện hardcode `<tenant>.localhost` và dựa vào Caddy strip `/platform`; muốn chạy trên ALB cần adapter
  (base domain thật + proxy strip) hoặc thay đổi phía app.
- `db_engine_version` và `mq_engine_version` đang pin; xác nhận version tồn tại ở region trước apply đầu tiên.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
