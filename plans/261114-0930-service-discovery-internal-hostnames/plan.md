---
title: "Service discovery: internal hostnames and idempotent platform domain"
description: "Let Platform answer on internal DNS names so a service-to-service call does not 404, and make domain registration safe with several replicas."
status: pending
priority: P1
effort: S
branch: dev
tags: [infra, backend, api, tech-debt]
blockedBy: []
blocks: [261114-1038-ecs-fargate-deployment]
created: 2026-11-14
---

# Service discovery: internal hostnames và platform domain idempotent

## Outcome

Platform trả lời được trên nhiều hostname cùng lúc — hostname admin công khai và mọi FQDN nội bộ mà cluster dùng —
và việc đăng ký domain trở nên idempotent nên nhiều replica khởi động đồng thời không làm pod chết.

## Why (evidence)

Kết luận từ `plans/reports/advise-261114-0930-service-discovery-and-loadbalancer.md`: Kubernetes Service DNS đã giải
bài toán "nhiều instance, IP động", nên không cần Eureka. Nhưng discovery không giải được Host header, và đó mới là
blocker thật.

- `PlatformBootstrap.java:32-35` đăng ký đúng **một** hostname lấy từ `platform.tenancy.platform-hostname`. Không có
  cách khai báo hostname thứ hai.
- `TenantContextFilter.java:38-40` (Platform) và `SuiteTenantFilter.java:33` (Billing, qua `core-suite`) đều lấy tenant
  từ `request.getServerName()`, chỉ bypass `/actuator/health`. Một FQDN nội bộ không có trong `tenant_domains` nhận
  404 ở `/oauth/token` và `/internal/replica/{stream}`.
- `EnsurePlatformDomainUseCase.java:36-42` làm check-then-insert; `JooqTenantDomainRepository.java:23-32` dùng insert
  thường; `V1__baseline.sql:51` khai báo `hostname text not null unique`. Compose chạy một replica nên không lộ;
  Kubernetes chạy nhiều replica sẽ làm một pod chết lúc startup.
- `PlatformBootstrap` là `ApplicationRunner` thuần, không có lease, nên mọi replica đều chạy phần đăng ký domain.

## Dependencies

Không phụ thuộc plan nào. Plan này chặn `261114-0931-kubernetes-deployment` (`blocks`), vì manifest cần khả năng khai
báo hostname nội bộ trước khi có ích.

## Non-goals

- Không thêm Eureka hay bất kỳ registry nào. Kubernetes Service DNS là cơ chế discovery.
- Không đổi bất biến "tenant luôn lấy từ host đã resolve" trong `SuiteActorResolver`.
- Không bypass Host resolution cho path nội bộ.
- Không dùng `spring-cloud-kubernetes` discovery.
- Không đổi schema `tenant_domains`.

## Phases

| # | Phase | Depends on | Status |
|---|-------|-----------|--------|
| 1 | [Idempotent platform domain registration](./phase-01-idempotent-platform-domain.md) | none | Pending |
| 2 | [Internal hostnames in configuration](./phase-02-internal-hostnames.md) | 1 | Pending |
| 3 | [Environment and documentation](./phase-03-environment-and-docs.md) | 1, 2 | Pending |

Phase 1 phải land trước, vì phase 2 gọi cùng use case nhiều lần thay vì một lần — nếu chưa idempotent thì fan-out sẽ
làm tăng nguy cơ đụng unique constraint chứ không giảm.

## Cross-cutting verification

- `cd platform && ./gradlew build`
- `cd core && ./gradlew build`
- `cd billing && ./gradlew build`
- `docker compose up -d --build --wait && ops/smoke/isolation.sh && ops/smoke/suite-smoke.sh`
- `git diff docker-compose.yml` rỗng (plan này không sửa compose)

## Global risks

- `onConflict(...).doNothing()` đổi ngữ nghĩa của `add()` trên port. Mitigation: thêm method mới `addIfAbsent` thay vì
  đổi `add()`, để caller khác (nếu có) giữ nguyên hành vi insert-thẳng.
- Đăng ký nhiều hostname làm chậm startup. Mitigation: số hostname nội bộ là một hoặc hai; mỗi lần đăng ký là một
  transaction ngắn.
- Nếu `internalHostnames` bị đặt mặc định sai, compose sẽ đăng ký domain lạ. Mitigation: test khẳng định list rỗng khi
  env không được set.

## Unresolved questions

Không có. Quyết định đã chốt: đăng ký nhiều hostname (không bypass filter), dùng DNS thuần (không Eureka), mặc định
rỗng để compose không đổi.
