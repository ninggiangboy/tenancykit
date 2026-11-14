---
phase: 2
title: "Managed data: RDS and Amazon MQ"
status: completed
priority: P2
effort: M
dependencies: [1]
---

# Phase 2: Managed data: RDS and Amazon MQ

## Goal

Dựng RDS Postgres (4 database: platform, billing, tenant, keycloak + owner/app role mỗi database) và Amazon MQ
(RabbitMQ, user riêng mỗi service), với mọi password trong AWS Secrets Manager. Tái tạo `ops/postgres/init/00-databases.sh`
và `ops/rabbitmq/declare-users.sh` trên managed services.

## Findings addressed

- `docker-compose.yml` dùng một Postgres container với init script `ops/postgres/init/00-databases.sh` tạo một owner role
  + một app role + một database cho mỗi service (platform, billing, tenant).
- `ops/rabbitmq/declare-users.sh` tạo một broker user mỗi service, giới hạn theo exchange/queue riêng.
- `billing-migrate` chạy Flyway với `BILLING_MIGRATION_DB_USER` (owner), còn api/worker kết nối bằng `BILLING_DB_USER`
  (app role, Flyway off). Platform/tenant migrate lúc start bằng owner role.
- RDS **không** tự chạy init script như container Postgres — cần một bước init rõ ràng.

## Files to create / modify

- Create `ops/terraform/rds/instance.tf`, `rds/subnet-group.tf`, `rds/databases-roles.tf`.
- Create `ops/terraform/mq/broker.tf`, `mq/users.tf`.
- Create `ops/terraform/secrets/secrets.tf`.

## Tasks

1. `rds/instance.tf`: `aws_db_instance` Postgres (version khớp compose `postgres:18`), `db_subnet_group_name` = private
   subnet (phase 1), `publicly_accessible = false`, `skip_final_snapshot = true` (dev), `deletion_protection = false`
   (dev). Master username/password từ Secrets Manager.
2. `rds/databases-roles.tf` — **RDS không quản lý database/role qua resource Terraform**, nên chọn một cách và ghi lý do:
   - **a)** One-shot ECS task chạy image `postgres:18` với `psql` thực thi cùng SQL như `00-databases.sh` (đã chuyển thành
     một file `.sql` hoặc giữ shell), chạy trong pipeline sau khi RDS up. Đồng bộ với mô hình one-shot task của migrate.
   - **b)** Terraform provider `cyrilgdn/postgresql` kết nối tới RDS (cần đường mạng từ nơi chạy terraform tới RDS — qua
     bastion hoặc chạy trong VPC) và tạo database/role.
   - Khuyến nghị **a)** vì không cần đường mạng tới private subnet từ máy chạy terraform, và dùng lại được pattern one-shot
     task.
3. `mq/broker.tf`: `aws_mq_broker` engine `RabbitMQ`, `deployment_mode = SINGLE_INSTANCE` (dev), private subnet,
   `publicly_accessible = false`. `mq/users.tf`: `aws_mq_user` cho `platform` và `billing` (user + password + quyền giới
   hạn như `declare-users.sh`; Amazon MQ dùng `console_access`/`groups` hạn chế, ghi rõ mapping).
4. `secrets/secrets.tf`: mỗi secret là một `aws_secretsmanager_secret` (random password hoặc nhập). Ít nhất:
   `POSTGRES_SUPERUSER_PASSWORD`, `PLATFORM_MIGRATION_DB_PASSWORD`, `PLATFORM_DB_PASSWORD`, `BILLING_MIGRATION_DB_PASSWORD`,
   `BILLING_DB_PASSWORD`, `TENANT_MIGRATION_DB_PASSWORD`, `TENANT_DB_PASSWORD`, `PLATFORM_RABBITMQ_PASSWORD`,
   `BILLING_RABBITMQ_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD`. Export ARN qua `outputs.tf` để
   phase sau tham chiếu.
5. Ghi comment: vai trò owner/app tách biệt phải giữ nguyên — migrate chạy owner, runtime chạy app role. Đây là mô hình
   bảo mật hiện tại, không gộp.

## Verification

- `terraform -chdir=ops/terraform validate`
- `terraform -chdir=ops/terraform plan` (cần AWS creds) — xem resource sẽ tạo, đặc biệt RDS/MQ.
- Sau apply (cần sandbox): kết nối psql tới RDS, `\l` thấy 4 database; `\du` thấy owner/app role mỗi service.
- `grep -rn "BILLING_DB_PASSWORD\|PLATFORM_DB_PASSWORD" ops/terraform/` → chỉ thấy reference ARN, không thấy giá trị thật.

## Success criteria

- [ ] RDS Postgres private, 4 database + owner/app role mỗi database. (Cần apply để chứng minh.)
- [ ] Amazon MQ RabbitMQ private, user `platform` + `billing`. (Cần apply để chứng minh.)
- [x] Mọi password trong Secrets Manager; `.tf` chỉ reference ARN.
- [x] Bước init database/role được chọn và có lý do ghi lại.

## Risks

- **RDS không tự init.** Bỏ bước này thì mọi Flyway migrate thất bại vì database/role chưa tồn tại. Mitigation: one-shot
  init task là bắt buộc, chạy trước mọi service (phase 5 pipeline).
- **Amazon MQ quyền user.** `declare-users.sh` giới hạn theo exchange/queue; `aws_mq_user` dùng nhóm quyền (groups) nên
  mapping không phải 1-1. Mitigation: tài liệu hóa mapping và chấp nhận quyền rộng hơn ở mức nhóm nếu cần; ghi rõ.
- **RDS version drift.** Compose dùng `postgres:18`; RDS phải chọn engine version tương đương còn được hỗ trợ. Mitigation:
  pin version trong variable, không dùng `latest`.

## Security considerations

RDS/MQ private subnet, không public access. Password ngẫu nhiên trong Secrets Manager, không trong `.tf`/`.tfvars`. Giữ
tách owner/app role: runtime không bao giờ dùng owner credential.

## Next steps

Phase 3 (Keycloak) dùng database `keycloak` và secret từ đây; phase 4/5 dùng database + MQ user tương ứng.
