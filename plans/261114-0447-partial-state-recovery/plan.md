---
title: "Khắc phục partial state ở ba biên service"
description: "Đóng sáu lỗ partial state không tự phục hồi ở biên tenant→billing và platform→keycloak, kèm kiểm chứng thực thi cho từng lỗ."
status: completed
priority: P1
effort: 2d
branch: dev
tags: [bugfix, backend, api, critical]
blockedBy: []
blocks: []
created: 2026-11-14
---

# Khắc phục partial state ở ba biên service

## Outcome

Sáu trạng thái nửa vời hiện có đều trở thành **hoặc hoàn tất, hoặc tự phục hồi được, hoặc dừng an toàn kèm lý do**. Không thêm saga, không 2PC, không tách database. Billing nội bộ không đổi vì nó đã đúng: một database, một `UnitOfWork`, outbox + inbox + idempotency trên chính row nghiệp vụ.

## Why (evidence)

Distributed transaction **không tồn tại và không cần** — Billing là modular monolith trên một PostgreSQL. Nhưng "không cần saga" không đồng nghĩa "an toàn": ba biên không chung transaction đang để lại trạng thái không ai dọn.

- **P0-1** `tenant/modules/execution/.../ExecuteOrdersUseCase.java:110-127` — `settle()` gọi `charge(order)` rồi `release(order)` **vô điều kiện ở cả hai nhánh**. `ChargeService.charge` (`ChargeService.java:62`) đã gọi `reservation.capture(...)`, mà `Reservation.capture` (`Reservation.java:96-98`) `wallet.unhold(amount)` toàn bộ và `close(CAPTURED)`. `release` qua HTTP (`ReservationController.java:77-81`) dùng `ReleaseReservationUseCase.execute(id, caller)` tức `tolerateClosed=false`, nên trên reservation đã capture ném `ConflictException` → 409 → transaction local rollback → order requeue → hết `maxAttempts` → order `FAILED` **trong khi tiền đã bị thu**.
- **P0-2** `platform/contexts/access/.../BootstrapPlatformAdminUseCase.java:62-80` — `createUser.execute(...)` commit ở transaction riêng với `publishNew` ở version 1 và permission set **rỗng** (role chưa assign), rồi transaction thứ hai mới tạo role + assign + `publishFor`. Nếu transaction 2 fail, admin đăng nhập được nhưng **zero permission**; lần boot sau `if (users.findByEmail(platform, email).isPresent()) return;` bỏ qua vĩnh viễn.
- **P0-3** `core/core-platform-sync/.../ReplicaBootstrap.java:63-86` + `ReplicaWriter.tenantsMissing()` — `needsRebuild()` chỉ kiểm tra stream **đầu tiên** (`ReplicaStream.TENANTS`). Rebuild đứt sau stream đó thì lần boot sau bỏ qua toàn bộ và `listener.start()` chạy tiếp: replica thiếu `tenant_domains` (mọi host 404), `user_grants`, `app_entitlements`, và chỉ tự lành khi từng row đó tình cờ đổi.
- **P1-1** `CreateUserUseCase.java:52-58, 91-93` và `ProvisionTenantUseCase.java:64-71` — compensation `deleteIdentity(subject)` nằm trong `catch` và có thể ném, che exception gốc. Identity mồ côi ở Keycloak khiến retry tạo username trùng (`{tenantCode}:{email}`) → Keycloak 409, mà `KeycloakIdentityProvider.createUser` không map 409 và không lookup, nên email đó **không tạo lại được** cho tới khi xoá tay.
- **P1-2** `ManageUserUseCase.java:39-62` — `DISABLE` chỉ đổi Platform DB và publish replica; **không** gọi Keycloak. Với Billing thì an toàn (`SuiteActorResolver.java:82` lọc `status == ACTIVE` từ replica), nhưng user vẫn lấy được realm token cho client khác.
- **P1-3** `tenant/modules/resource/.../ManageOrderUseCase.java:35-51` và `tenant/modules/customer/.../ManageWalletSettingUseCase.java:74-82` — gọi HTTP Billing **bên trong** `unitOfWork.transactional`. Tự lành khi retry nên hạ ưu tiên, nhưng vi phạm nguyên tắc của chính repo: *"no transaction may wrap an HTTP call"*.

## Correction to the earlier advice

Hai điểm trong khuyến nghị trước đã bị **bác bỏ bằng kiểm chứng code**, và plan này theo kết luận đã sửa:

1. **Không làm HTTP release tolerant với `CAPTURED`.** `billing/apps/app-boot/src/test/java/dev/ngb/billing/money/MoneyIntegrationTest.java:433` assert **cố ý** rằng `releaseReservation.execute(held.id())` trên reservation đã capture ném `ConflictException`. Đó là semantics được ghi trong `ReleaseReservationUseCase.java:56-57` ("The administrator's release: a hold that is not active is a conflict"). Đổi nó sẽ phá hợp đồng đã có chủ đích. Sửa đúng là **bỏ lời gọi thừa ở phía tenant**.
2. **`release(order)` là dead code ở cả hai nhánh `settle()`.** Nhánh thất bại cũng vô nghĩa: khi `charge` trả `FAILED` do `insufficient_funds`, `ChargeService` đã không `capture` (reservation vẫn `ACTIVE`), và `release` sẽ nhả hold — nhưng transaction local đã rollback nên order về `QUEUED`, lần chạy sau lại capture tiếp. Phase 1 xử lý cả hai nhánh, không chỉ nhánh thành công.

## Non-goals

- Không thêm saga framework, process manager, TCC, 2PC/XA, Seata.
- Không tách database Billing, không tách `pricing`/`usage` ra service riêng.
- Không thêm generic idempotency table (đã bị xoá có chủ đích ở `V19__drop_idempotency_keys.sql`).
- Không gộp các call money thành một endpoint "one for all" — `capture` **đã là** thao tác gộp đó.
- Không đổi wire format event, không đổi OpenAPI contract hiện có.
- Không đồng bộ Keycloak sang chiều ngược lại (Keycloak → Platform).

## Phases

| # | Phase | Depends on | Status |
|---|-------|-----------|--------|
| 1 | [Settle không gọi release thừa](./phase-01-settle-without-release.md) | none | Done |
| 2 | [Bootstrap admin thành repair idempotent](./phase-02-bootstrap-admin-repair.md) | none | Done |
| 3 | [Rebuild replica có completion marker](./phase-03-replica-completion-marker.md) | none | Done |
| 4 | [Compensation Keycloak không che lỗi](./phase-04-keycloak-compensation.md) | 2 | Done |
| 5 | [Tách HTTP ra khỏi transaction ở tenant](./phase-05-tenant-http-out-of-transaction.md) | 1 | Done |

Ba phase đầu độc lập nhau về file nên có thể làm song song. Phase 4 chạm cùng file với phase 2 nên phải sau. Phase 5 chạm cùng module với phase 1 nên phải sau.

## Cross-cutting verification

Mỗi phase có lệnh kiểm chứng riêng. Ngoài ra, toàn plan phải qua:

```bash
cd core     && ./gradlew build
cd platform && ./gradlew build
cd billing  && ./gradlew build
cd tenant   && ./gradlew build
docker compose up -d --build --wait && ops/smoke/isolation.sh && ops/smoke/suite-smoke.sh
```

Build của mỗi service đã bao gồm isolation gate và ArchUnit, nên một vi phạm biên sẽ làm fail chứ không cần review tay.

## Baseline đã kiểm chứng

Trước khi viết plan, các bước sau đã được chạy thật (không suy luận):

- `cd tenant && ./gradlew :modules:execution:test` — **xanh**, và `runsRecordsUsageCapturesReleasesAndCompletes` **pass**. Nghĩa là test hiện tại đang khoá chặt hành vi sai của P0-1; phase 1 phải sửa chính test này, không thể chỉ sửa code.
- Mọi task path trong các lệnh Verification đã được `--dry-run` xác nhận tồn tại: `:modules:execution:test`, `:modules:resource:test`, `:modules:customer:test` (tenant), `:core-platform-sync:test`, `:core-replica:test` (core).
- `grep -rn "ReplicaBootstrap|ReplicaProperties|SnapshotClient" platform/apps/app-boot/src/main/java/` trả **rỗng** → Platform là producer thuần, không cần migration `replica_state`.
- `MoneyIntegrationTest.java:433` assert **cố ý** `release` trên reservation đã capture ném `ConflictException` → semantics này không được đổi.
- `core/core-platform-sync/src/test/java/dev/ngb/core/sync/ReplicaRebuildIntegrationTest.java` đã có sẵn Testcontainers + fake transport → phase 3 mở rộng file này thay vì tạo test mới.
- `JooqAccessRepository.java:50-57` dùng `onConflict doNothing` → repair ở phase 2 an toàn khi gọi lặp.

## Global risks

- **Phase 3 thêm bảng vào DDL dùng chung `core-replica`.** Mọi app tiêu thụ replica phải có bảng đó. Mitigation: thêm vào `replica-tables.sql` (một nguồn DDL) và để `ReplicaSchemaAssertions` bắt app nào thiếu.
- **Phase 2 biến bootstrap từ "chạy một lần" thành "repair mỗi lần boot".** Nếu logic repair sai, mỗi lần restart sẽ ghi lại dữ liệu. Mitigation: `assign` đã idempotent (`JooqAccessRepository.java:50-57` dùng `onConflict doNothing`), và test khẳng định boot hai lần không tạo role/grant thứ hai.
- **Phase 1 đổi hành vi quan sát được:** một HTTP call biến mất khỏi mỗi lần settle. Đây là điểm đúng, không phải hồi quy; test cũ đang khoá bug nên phải sửa cùng lúc.
- **Không phase nào repair được dữ liệu đã hỏng trước đó** (order `FAILED` đã bị thu tiền, admin đã zero permission, replica đã thiếu row). Plan này chặn tái diễn, không hồi tố. Xem "Follow-up".

## Follow-up (nằm ngoài plan này)

- Một script đối chiếu một lần cho môi trường đã chạy: order `FAILED` có charge `SUCCEEDED` cùng `reservation_id`; admin không có grant nào; `count(tenant_domains)` lệch snapshot. Cần dữ liệu thật nên không đưa vào đây.
- Replica lag metric / alarm hiện **không tồn tại**. Không thuộc phạm vi partial-state recovery nhưng là lỗ hổng quan sát liên quan.

## Unresolved questions

Không có. Phạm vi đã chốt: sửa cả sáu phát hiện, kiểm chứng bằng test thực thi chứ không chỉ đọc code.

## Deviations during implementation

Ba điểm đã phải điều chỉnh khi thực thi, so với văn bản phase. Tất cả đều làm chặt hơn, không nới lỏng tiêu chí.

1. **Phase 2 — repair phải đọc user trong một transaction.** Plan giả định `users.findByEmail(...)` là một lời gọi
   thuần. Thực tế nó phải chạy trong `UnitOfWork`, vì row-level security lấy tenant từ transaction
   (`set_config('app.tenant_id', …, true)` là transaction-local). Đọc ngoài transaction luôn thấy rỗng, và chính điều
   đó làm guard cũ không những bỏ qua repair mà còn ném `ConflictException` ở boot thứ hai — test mới bắt được cả hai.

2. **Phase 5 — `CANCELING` giữ nguyên khi remote call ném, không chuyển sang `FAILED`.** Đoạn code mẫu trong phase
   gọi `pending.failed(reason, ...)`, nhưng chính success criteria của phase yêu cầu "Billing lỗi → order `CANCELING`
   (không mất trạng thái)" và reaper phải tìm được nó. Chuyển sang `FAILED` sẽ (a) làm reaper không bao giờ thấy order,
   và (b) tuyên bố sai rằng Billing chưa cancel — trong khi nó có thể đã cancel xong. Vì `CANCELING → CANCELING` không
   phải một cạnh hợp lệ trong transition map, tôi thêm `Order.cancelFailed(reason, now)` để ghi lý do mà **không** đổi
   trạng thái. Đây là cách thoả cả hai vế của phase.

3. **Phase 5 — reaper chỉ nhặt `CANCELING` cũ hơn `reservationTtl`.** Phase không nói rõ cutoff cho `CANCELING`. Dùng
   chung `reservationTtl` với `PENDING` là nhất quán: nó ngăn reaper giành một cancel đang chạy bình thường, và vẫn
   dọn được sau khi process chết. Integration test mô phỏng thời gian trôi bằng cách lùi `updated_at` của row.

Bước "conditional simplify" của workflow vượt ngưỡng (783 dòng / 33 file), nhưng repo không có subagent
`code-simplifier`, nên bước đó được bỏ qua và ghi nhận ở đây thay vì báo đã chạy.

## Cross-cutting verification evidence

```
cd core     && ./gradlew build   # BUILD SUCCESSFUL
cd platform && ./gradlew build   # BUILD SUCCESSFUL
cd billing  && ./gradlew build   # BUILD SUCCESSFUL
cd tenant   && ./gradlew build   # BUILD SUCCESSFUL
```

Phần `docker compose up -d --build --wait && ops/smoke/...` **chưa chạy**: compose cần `.env` với các biến bí mật
(`BILLING_DB_PASSWORD`, `BILLING_SECRETS_MASTER_KEY`, …) mà repo không ship, và tôi không tạo secret giả. Đó là giới
hạn còn lại của lần verify này.

