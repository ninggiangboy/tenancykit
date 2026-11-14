---
phase: 3
title: "Environment and documentation"
status: pending
priority: P2
effort: XS
dependencies: [1, 2]
---

# Phase 3: Environment and documentation

## Goal

Biến mới xuất hiện trong `.env.example` với giá trị mặc định rỗng, và tài liệu nói rõ cơ chế discovery là Kubernetes
Service DNS chứ không phải một registry riêng — kèm lý do.

## Findings addressed

- `.env.example:12` chỉ có `PLATFORM_HOSTNAME=platform.localhost`; không có biến cho hostname nội bộ.
- `docs/runtime-profiles.md` mô tả khuôn runtime nhưng không nói gì về discovery giữa các service.
- `docs/architecture.md` ghi các quyết định lớn nhưng chưa có dòng nào về "vì sao không dùng Eureka".

## Files to create / modify

- Modify `.env.example` — thêm `PLATFORM_INTERNAL_HOSTNAMES=` (rỗng) kèm comment.
- Modify `docs/runtime-profiles.md` — thêm mục ngắn "Service discovery" giải thích: hostname quyết định tenant scope;
  một deployment có thể khai báo thêm hostname nội bộ; registry không cần vì orchestrator đã có DNS.
- Modify `docs/architecture.md` — thêm một dòng vào Decision ledger: discovery dùng DNS của orchestrator, không dùng
  registry ứng dụng; lý do là Host→tenant đã là cơ chế định tuyến và registry thêm một thành phần stateful.

## Tasks

1. `.env.example`, dưới `PLATFORM_HOSTNAME`:

   ```
   # Extra hostnames Platform answers on, besides PLATFORM_HOSTNAME: internal DNS names a deployment uses to reach it
   # service-to-service. Empty for docker compose; on Kubernetes set the Service FQDN. Every value is registered as a
   # PLATFORM domain at startup, so a hostname here must also be the Host header a caller sends.
   PLATFORM_INTERNAL_HOSTNAMES=
   ```

2. `docs/runtime-profiles.md`: thêm mục ngắn (5-8 dòng) nêu chuỗi nhân quả: caller gửi Host → Platform/Billing resolve
   Host thành tenant scope → một FQDN nội bộ chưa đăng ký nhận 404 → khai báo nó qua `PLATFORM_INTERNAL_HOSTNAMES`.
   Nêu rõ `PLATFORM_TOKEN_ISSUER` và `BILLING_PLATFORM_ISSUER` **không** đổi khi chuyển sang DNS nội bộ.

3. `docs/architecture.md` Decision ledger: thêm dòng `Service discovery` với Decision / Why, theo đúng format bảng
   hiện có (cột `Area | Decision | Why`).

4. Kiểm tra không có tài liệu nào còn khẳng định chỉ có một hostname: `grep -rn "platform-hostname" docs/`.

## Verification

- `grep -n "PLATFORM_INTERNAL_HOSTNAMES" .env.example`
- `grep -n "Service discovery" docs/architecture.md docs/runtime-profiles.md`
- `git diff --stat docs/ .env.example` → chỉ hai file docs và `.env.example`, không có file code.

## Success criteria

- [ ] `.env.example` có biến mới, giá trị rỗng, comment giải thích hệ quả việc đặt giá trị.
- [ ] `docs/runtime-profiles.md` nói rõ Host→tenant và cách khai báo hostname nội bộ.
- [ ] `docs/architecture.md` có dòng quyết định discovery, không mâu thuẫn các dòng khác.
- [ ] Không tài liệu nào còn nói issuer đổi theo base URL.

## Risks

Sửa `docs/architecture.md` có thể đụng bảng Decision ledger lớn. Mitigation: chỉ thêm một dòng, không sắp xếp lại bảng.

## Security considerations

Không ghi giá trị thật của deployment nào vào tài liệu. Chỉ mô tả biến và mặc định rỗng.

## Next steps

Plan `261114-0931-kubernetes-deployment` dùng `PLATFORM_INTERNAL_HOSTNAMES` trong ConfigMap và đặt giá trị là
`platform.<namespace>.svc.cluster.local`.
