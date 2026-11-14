---
title: Hai advise và hai plan cho service discovery và Kubernetes
date: 2026-11-14
summary: Ghi lại 2 báo cáo advise và tách thành 2 plan thực thi cho Host nội bộ và deploy Kubernetes
---

# Hai advise và hai plan cho service discovery và Kubernetes

## What happened

Hai phiên `/ak-advise` trước đó kết luận trong hội thoại nhưng chưa được lưu. Hôm nay ghi lại thành file và tách
thành hai plan độc lập, theo lựa chọn "2 advise + 2 plan tách" của chủ dự án.

Bối cảnh: yêu cầu ban đầu là thêm Spring Cloud LoadBalancer + Netflix Eureka. Qua phỏng vấn, mục tiêu thật là "nhiều
instance, IP động" trên Kubernetes, Eureka không bắt buộc, và sẽ có thêm service mới.

## Change

Bốn tài liệu mới, không đụng code:

- `plans/reports/advise-261114-0930-service-discovery-and-loadbalancer.md` — kết luận không dùng Eureka; Kubernetes
  Service DNS giải bài toán discovery; blocker thật là Host→tenant.
- `plans/reports/advise-261114-0931-kubernetes-deployment.md` — phạm vi K8s: chỉ app, hạ tầng external, giữ song song
  docker compose, validate bằng kubeconform.
- `plans/261114-0930-service-discovery-internal-hostnames/` — 3 phase: đăng ký domain idempotent, list hostname nội
  bộ, env + tài liệu.
- `plans/261114-0931-kubernetes-deployment/` — 4 phase: nền manifest, Platform workload, Billing workload + Job
  migrate, validate + CI + docs.

Hai plan liên kết hai chiều: plan discovery `blocks` plan Kubernetes; plan Kubernetes `blockedBy` plan discovery.

## Evidence

Ba phát hiện định hình nội dung, đều đọc trực tiếp từ code:

- `EnsurePlatformDomainUseCase:34-45` check-then-insert; `JooqTenantDomainRepository:23-32` insert thường;
  `V1__baseline.sql:51` có `hostname ... unique`. Compose chạy 1 replica nên không lộ; Kubernetes ≥2 replica sẽ làm
  pod chết lúc startup.
- Platform `TenantContextFilter:38-40` và Billing `SuiteTenantFilter:33` lấy tenant từ `request.getServerName()`, chỉ
  bypass `/actuator/health`. FQDN nội bộ chưa đăng ký → 404 ở `/oauth/token` và `/internal/replica/*`.
- Issuer ≠ base URL: `PLATFORM_TOKEN_ISSUER` và `BILLING_PLATFORM_ISSUER` phải giữ host công khai ổn định; chỉ
  `BILLING_PLATFORM_BASE_URL` và `BILLING_PLATFORM_JWK_SET_URI` đổi sang DNS nội bộ. Đây là bẫy đã có tiền lệ trong
  journal `2026-11-14-fix-issuer-fallback-traps`.

Cũng xác nhận: repo không có manifest Kubernetes nào (`ops/` chỉ có caddy, keycloak, postgres, rabbitmq, smoke,
loadtest); `spring-cloud-starter-openfeign:5.0.4` để LoadBalancer là `optional=true`, và resolve
`:core-feign:runtimeClasspath` cho 0 hit `loadbalancer`.

## Decision

Tách hai plan thay vì gộp, vì kết luận của advise discovery là "không cần registry" — gộp sẽ khiến plan Kubernetes
mang theo một phase gần như rỗng. Tách giữ mỗi plan một mục tiêu kiểm chứng được và giữ đúng quan hệ phụ thuộc thật:
không có hostname nội bộ thì manifest đúng cú pháp nhưng stack vẫn 404.

## Verification

- 2 file advise và 2 thư mục plan tồn tại; 7 phase file khớp bảng Phases trong hai `plan.md`.
- Mọi link `./phase-*.md` resolve; liên kết `blocks`/`blockedBy` nhất quán hai chiều.
- Frontmatter của hai `plan.md` parse được, `status: pending`.
- Không có giá trị secret thật trong bất kỳ file mới nào; `secret.example.yaml` chỉ là placeholder trong hướng dẫn.
- Task đã hydrate: 7 task, dependency theo đúng thứ tự phase.

## Next steps

Chạy `/ak-cook plans/261114-0930-service-discovery-internal-hostnames/plan.md` trước. Sau khi phase 1 và 2 land, plan
Kubernetes mới có nền để chạy. Hai câu hỏi còn mở cho lúc có cluster thật: ingress controller nào, và domain công
khai thật là gì.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
