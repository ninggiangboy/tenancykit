---
title: Tách core-replica thành ba module theo ranh giới state/fetch/policy
date: 2026-11-13
summary: "core-replica chỉ còn bản sao dữ liệu; snapshot chuyển sang core-platform-sync; token/tenant/entitlement chuyển sang core-suite và đổi tên Replica* thành Suite*."
---

# Tách core-replica thành ba module theo ranh giới state/fetch/policy

## What happened

`core-replica` gộp ba thứ khác vòng đời: schema cục bộ của dữ liệu Platform (`ReplicaTables`, `ReplicaWriter`,
`ReplicaEventHandler`, `replica-tables.sql`), việc kéo dữ liệu về (`SnapshotClient`, `ReplicaBootstrap`,
`ReplicaProperties`) và việc thực thi danh tính/tenant/quyền ở mỗi request (`SuiteJwtDecoders`, actor resolvers,
host resolver, hai filter, entitlements). Tên module chỉ đúng cho phần thứ nhất, và mọi app chỉ cần enforcement đều
phải kéo theo HTTP client.

Đã tách thành ba module, giữ một DAG: `core-replica` (state, không còn servlet/security/nimbus/HTTP),
`core-platform-sync` (snapshot client + bootstrap + properties), `core-suite` (token, host→tenant, actor,
entitlement). `core-suite` và `core-platform-sync` cùng phụ thuộc `core-replica`, không phụ thuộc nhau.
Class trong `core-suite` đổi `Replica*` → `Suite*`; `SuiteEntitlementFilter` nhận `String appCode` thay vì
`ReplicaProperties` để không phải phụ thuộc module sync.

## Decision

Ranh giới đặt ở "state / fetch / policy" chứ không phải "contract / impl". Module giữa giữ luôn
`ReplicaWriter` và `ReplicaEventHandler` vì module chỉ có hằng số jOOQ là một artifact không có hành vi.
Không thêm port `TenantDirectory`/`UserDirectory`: chỉ có một nguồn dữ liệu, abstraction chưa có variant thứ hai.
Không đổi key `suite.replica.*`, không đổi tên bảng, không sửa `plans/`.

## Evidence

`./gradlew build` xanh ở `core`, `platform`, `billing`, `tenant`; boundary test 10 rule mới (4 replica, 3 sync,
3 suite) đều chạy và pass; `./gradlew :core-suite:dependencies` không có `core-platform-sync`; test classpath của
Platform không còn `core-platform-sync`/`core-suite`.

## Flake observed, not caused

`InvoiceBrokerPublishIntegrationTest` fail một lần trong full build: `tenant-id` header nhận được thuộc tenant khác.
Pass khi chạy riêng và pass lại ở lần full build sau. Nghi vấn: test bind queue `#` vào `billing.events` và
`CloseBillingCycleUseCase.runOnce` đóng kỳ cho mọi tenant trong DB dùng chung, nên message của tenant khác có thể
đến trước. Chưa sửa; nếu tái diễn nên lọc theo tenant trong `receiveUntil`.

## Next steps

- Commit phần split và phần rename thành hai commit riêng, tách khỏi WIP `core-feign` đang có trong working tree.
- Nếu flake tái diễn, sửa `receiveUntil` để lọc message theo tenant của test.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
