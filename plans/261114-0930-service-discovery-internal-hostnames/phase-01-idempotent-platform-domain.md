---
phase: 1
title: "Idempotent platform domain registration"
status: pending
priority: P1
effort: S
dependencies: []
---

# Phase 1: Idempotent platform domain registration

## Goal

Đăng ký domain `PLATFORM` trở nên idempotent ở tầng database, để hai replica khởi động đồng thời không làm pod nào
chết vì vi phạm unique constraint trên `tenant_domains.hostname`.

## Findings addressed

- `EnsurePlatformDomainUseCase.java:34-45` đọc trước rồi ghi: hai transaction đồng thời đều thấy "chưa có" và đều
  insert.
- `JooqTenantDomainRepository.java:23-32` dùng `insertInto(...).execute()`, không có `onConflict`, nên vi phạm unique
  index ném ra ngoài.
- `V1__baseline.sql:48-56`: `hostname text not null unique`; không có ràng buộc nào khác chặn hai lần ghi giống nhau.
- `PlatformBootstrap` là `ApplicationRunner` không lease, nên mọi replica đều chạy bước này khi start.

## Files to create / modify

- Modify `platform/contexts/tenancy/src/application/java/dev/ngb/platform/tenancy/application/tenant/port/TenantDomainRepository.java`
  — thêm `boolean addIfAbsent(TenantDomain domain)`; giữ `add(TenantDomain)` nguyên vẹn.
- Modify `platform/contexts/tenancy/src/adapter/java/dev/ngb/platform/tenancy/adapter/tenant/persistence/JooqTenantDomainRepository.java`
  — cài `addIfAbsent` bằng `onConflict(TENANT_DOMAINS.HOSTNAME).doNothing()`, trả về `execute() == 1`.
- Modify `platform/contexts/tenancy/src/application/java/dev/ngb/platform/tenancy/application/tenant/command/EnsurePlatformDomainUseCase.java`
  — gọi `addIfAbsent`; chỉ publish `TenancyEvents.TenantDomainChanged` khi insert thực sự xảy ra; nhánh "đã tồn tại"
  đọc lại bằng `findByHostname` và trả về.
- Create `platform/contexts/tenancy/src/test/java/dev/ngb/platform/tenancy/application/tenant/command/EnsurePlatformDomainUseCaseTest.java`
  — nếu module có test source set và hạ tầng test phù hợp; nếu không, đặt integration test ở `app-boot` (xem Tasks 4).
- Create `platform/apps/app-boot/src/test/java/dev/ngb/platform/tenancy/PlatformDomainIdempotencyIntegrationTest.java`
  — test với PostgreSQL thật (Testcontainers) nếu module application không có test harness.

## Tasks

1. Thêm `addIfAbsent` vào port `TenantDomainRepository`. Ghi javadoc nêu rõ: `true` khi row được tạo, `false` khi
   hostname đã tồn tại; không ném khi trùng.
2. Cài đặt trong `JooqTenantDomainRepository`:

   ```java
   @Override
   public boolean addIfAbsent(TenantDomain domain) {
       return dsl.insertInto(TENANT_DOMAINS)
               .set(TENANT_DOMAINS.ID, domain.id())
               .set(TENANT_DOMAINS.TENANT_ID, domain.tenantId().map(TenantId::value).orElse(null))
               .set(TENANT_DOMAINS.HOSTNAME, domain.hostname().value())
               .set(TENANT_DOMAINS.KIND, domain.kind().name())
               .set(TENANT_DOMAINS.CREATED_AT, domain.createdAt())
               .set(TENANT_DOMAINS.VERSION, domain.version())
               .onConflict(TENANT_DOMAINS.HOSTNAME)
               .doNothing()
               .execute() == 1;
   }
   ```

   Đây là convention có sẵn: `JooqCustomerRepository.java:43-44`, `JooqPaymentRepository.java:40-41`,
   `JooqStripeEventRepository.java:51-52`, `JooqIncidentRepository.java:45`, `JooqRatingRepository.java:77-78`.

3. `EnsurePlatformDomainUseCase.execute` đổi sang: tạo `TenantDomain.platform(...)` như hiện tại, gọi
   `domains.addIfAbsent(domain)`; nếu `true` thì publish event và trả `domain`; nếu `false` thì
   `domains.findByHostname(name).orElseThrow(() -> new IllegalStateException(...))`.
   Giữ nguyên `unitOfWork.transactional(...)`, `Hostname.fromHostHeader(...)`, `ids.newId()` và `clock.now()`.
4. Test (bắt buộc, ở `app-boot` nếu cần PostgreSQL thật):
   - Gọi `execute("platform.localhost")` hai lần liên tiếp → không ném, `tenant_domains` có đúng một row, event publish
     đúng một lần.
   - Gọi với hostname viết hoa và có port (`"Platform.LocalHost:8080"`) → vẫn khớp cùng row, vì
     `Hostname.fromHostHeader` đã chuẩn hoá.
5. Chạy `cd platform && ./gradlew build`.

## Verification

- `cd platform && ./gradlew :contexts:tenancy:build`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*PlatformDomainIdempotency*'`
- `cd platform && ./gradlew build`
- `grep -n "onConflict" platform/contexts/tenancy/src/adapter/java/dev/ngb/platform/tenancy/adapter/tenant/persistence/JooqTenantDomainRepository.java`
  → có đúng một hit.

## Success criteria

- [ ] `TenantDomainRepository` có `addIfAbsent` và `add` vẫn giữ nguyên hành vi.
- [ ] `EnsurePlatformDomainUseCase` không còn check-then-insert.
- [ ] Chạy đăng ký hai lần liên tiếp: không exception, đúng một row, đúng một event.
- [ ] `./gradlew build` ở `platform/` xanh.

## Risks

Đổi ngữ nghĩa đăng ký domain có thể ảnh hưởng `AddTenantDomainUseCase` nếu nó dùng chung repository theo cách khác.
Mitigation: chỉ thêm method mới, không sửa `add()`; `AddTenantDomainUseCase.java:50-52` đã tự kiểm tra trùng và ném
`HostnameAlreadyMappedException`, nên hành vi của nó không đổi.

Nếu `doNothing()` không trả `1` trên PostgreSQL với một số driver, kiểm tra lại qua test; cách thay thế là
`fetchOne()` vào `RETURNING id`. Chỉ dùng cách thay thế khi test chứng minh cách đầu không đúng.

## Security considerations

Không thay đổi mô hình bảo mật. Domain `PLATFORM` vẫn không có `tenant_id` (ràng buộc `V1__baseline.sql:55`), nên
không thể bị dùng để chiếm scope tenant.

## Next steps

Phase 2 dùng chính use case này để đăng ký nhiều hostname, nên fan-out chỉ an toàn sau khi phase 1 land.
