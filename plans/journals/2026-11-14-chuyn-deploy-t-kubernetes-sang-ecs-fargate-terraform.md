---
title: Chuyển deploy từ Kubernetes sang ECS Fargate + Terraform
date: 2026-11-14
summary: "Advise so sánh ECS và k8s, chốt ECS Fargate + Terraform, tạo plan thay thế và đánh dấu plan Kubernetes cancelled"
---

# Chuyển deploy từ Kubernetes sang ECS Fargate + Terraform

# Chuyển deploy từ Kubernetes sang ECS Fargate + Terraform

## What happened

Hai plan trước hướng tới Kubernetes. Chủ dự án hỏi liệu triển khai đơn giản hơn bằng AWS ECS thì sao. Qua `/ak-advise`
phỏng vấn, chốt chuyển hướng sang ECS Fargate + Terraform, rồi `/ak-plan` tạo plan thay thế.

## Change

Bốn quyết định chốt qua phỏng vấn:

- IaC full (Terraform) thay vì chỉ manifest.
- Keycloak self-host trên ECS (kèm RDS riêng).
- Postgres + RabbitMQ dùng managed AWS (RDS + Amazon MQ).
- Công cụ IaC là Terraform (HCL).

Tài liệu mới / sửa:

- `plans/261114-1038-ecs-fargate-deployment/` — plan mới 6 phase.
- `plans/261114-0931-kubernetes-deployment/plan.md` — đánh dấu `cancelled`, ghi chú superseded.
- `plans/261114-0930-service-discovery-internal-hostnames/plan.md` — đổi `blocks` sang plan ECS mới.

## Evidence

- `docker-compose.yml` chạy 4 workload + `billing-migrate`; Caddy route thuần Host header (`ops/caddy/Caddyfile`).
- Plan 0930 vẫn bắt buộc cho ECS: Service Connect cho DNS nội bộ nhưng không sửa Host header; `TenantContextFilter` lấy
  tenant từ `request.getServerName()` nên FQDN nội bộ chưa đăng ký vẫn 404.
- AWS Emulator (`docs.aws.amazon.com/emulator`) chỉ giả lập control-plane API, không chạy container/data plane, không
  evaluate IAM — không thể check "4 service RUNNING + healthy + smoke test". `docker compose` mới là local check đúng;
  deployment semantics cần AWS sandbox thật.

## Decision

Chuyển từ k8s sang ECS Fargate + Terraform vì quy mô nhỏ (3 workload + Keycloak) không cần chi phí vận hành cluster.
Nhưng ghi nhận trade-off: phần khó (migrate ordering, Host-header discovery, issuer trap) không biến mất khi sang ECS.

## Verification

- Plan mới có 6 phase, frontmatter parse được, `blockedBy` trỏ 0930.
- Cross-reference nhất quán: 0930 `blocks` ECS, 0931 `cancelled`.
- `docker-compose.yml` không đổi.

## Next steps

Chạy `/ak-cook plans/261114-0930-service-discovery-internal-hostnames/plan.md` trước (plan ECS bị chặn bởi 0930), rồi
mới `/ak-cook` plan ECS. Câu hỏi còn mở: domain công khai thật, tên Service Connect namespace, backend state Terraform
(S3 vs local).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
