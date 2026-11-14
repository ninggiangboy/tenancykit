---
phase: 3
title: "Rebuild replica có completion marker"
status: completed
priority: P1
effort: 5h
dependencies: []
---

# Phase 3: Rebuild replica có completion marker

## Goal

Một rebuild bị đứt giữa chừng phải được chạy lại ở lần boot sau, thay vì bị coi là đã xong vì stream đầu tiên đã đầy.

## Findings addressed

- `ReplicaBootstrap.java:63-86` — `needsRebuild()` chỉ hỏi `writer.tenantsMissing()`, tức stream **đầu tiên** trong `ReplicaStream` order (`TENANTS` → `TENANT_DOMAINS` → `TENANT_SYSTEMS` → `USER_ACCESS` → `ENTITLEMENTS`, xem `ReplicaStream.java:22-61`).
- `ReplicaWriter.java:241-249` — `tenantsMissing()` chỉ đếm `tenants` khác `platform`.
- `ReplicaBootstrap.java:71-84` — `run(...)` gọi `needsRebuild()`, có thể bỏ qua `rebuild()` hoàn toàn, rồi **luôn** `listener.start()`. Nếu rebuild đứt sau `tenants`, replica thiếu `tenant_domains` (mọi host 404), `user_grants` (mọi request 401), `app_entitlements`.
- `ReplicaBootstrap.java:88-99` — mỗi page commit riêng, nên một failure giữa stream để lại dữ liệu một phần đã ghi, không có dấu hiệu nào cho biết rebuild chưa hoàn tất.

## Key insight

Guard hiện tại trả lời sai câu hỏi. Nó hỏi *"replica có rỗng không?"* trong khi câu cần hỏi là *"rebuild đã hoàn tất lần nào chưa?"*. Hai câu trùng nhau chỉ khi rebuild chưa bao giờ bắt đầu.

Sửa bằng một **completion marker**: một row ghi lại thời điểm rebuild hoàn tất **toàn bộ** stream. `needsRebuild` trở thành `marker chưa có || tenantsMissing()`. Marker chỉ được ghi sau khi vòng lặp qua mọi `ReplicaStream` kết thúc không ném.

Đặt ở `core-platform-sync`, không trong `billing/`, vì mọi app tiêu thụ replica dùng chung đúng một đường rebuild này. Một app onboarding sau này thừa hưởng luôn tính chất đúng.

**Không** làm per-stream progress table: nó granular hơn nhu cầu, và upsert đã idempotent nên rebuild lại toàn bộ là an toàn. Chi phí của per-stream chỉ đáng khi một stream đơn lẻ rất lớn — chưa có bằng chứng nào.

## Files to create / modify

- Modify `core/core-replica/src/main/resources/dev/ngb/core/replica/replica-tables.sql` — thêm `replica_state`. Đây là **một nguồn DDL** cho mọi app (`ReplicaSchemaAssertions.java:29` đọc chính resource này).
- Modify `core/core-replica/src/main/java/dev/ngb/core/replica/ReplicaTables.java` — thêm `REPLICA_STATE` và các field.
- Create `core/core-replica/src/main/java/dev/ngb/core/replica/ReplicaState.java` — đọc/ghi marker bằng `DSLContext`.
- Modify `core/core-platform-sync/src/main/java/dev/ngb/core/sync/ReplicaBootstrap.java` — `needsRebuild()` đọc marker; `rebuild()` ghi marker sau khi xong mọi stream.
- Modify `core/core-platform-sync/src/test/java/dev/ngb/core/sync/ReplicaRebuildIntegrationTest.java` — thêm ba ca; file này đã có Testcontainers, `RecordingTransport` và `Snapshot` sẵn dùng.
- Modify `core/core-platform-sync/src/test/java/dev/ngb/core/sync/SnapshotClientTest.java` — không bắt buộc; chỉ sửa nếu constructor `ReplicaBootstrap` đổi ảnh hưởng. File này không dựng `ReplicaBootstrap`, nên để nguyên.
- Create `billing/apps/app-boot/src/main/resources/db/migration/V26__replica_state.sql` — migration cho Billing. Billing là **consumer duy nhất** của replica trong production; đã kiểm chứng ở bước 6.

## Implementation steps

1. Thêm vào cuối `replica-tables.sql`, với comment nêu lý do:

   ```sql
   -- One row: when a full rebuild last finished every stream. `ReplicaBootstrap` reads it to decide whether the
   -- replica is complete; a rebuild that starts (first stream lands) but never finishes must be retried on the next
   -- boot, which a row count per stream cannot tell apart from "already built".
   create table if not exists replica_state (
       id         smallint primary key default 1,
       rebuilt_at timestamptz not null,
       check (id = 1)
   );
   ```

   `id bigint/smallint primary key default 1` + `check (id = 1)` là cách giữ đúng một row mà không cần thêm cột.

2. Thêm vào `ReplicaTables.java`:

   ```java
   public static final Table<Record> REPLICA_STATE = DSL.table(DSL.name("replica_state"));
   public static final Field<Short> REPLICA_STATE_ID = DSL.field(DSL.name("replica_state", "id"), SQLDataType.SMALLINT);
   public static final Field<OffsetDateTime> REPLICA_STATE_REBUILT_AT =
           DSL.field(DSL.name("replica_state", "rebuilt_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
   ```

   Chỉ **thêm** field, không đổi tên gì hiện có.

3. Tạo `ReplicaState`:

   ```java
   public class ReplicaState {
       private final DSLContext dsl;
       public ReplicaState(DSLContext dsl) { this.dsl = dsl; }

       /** True when a full rebuild has finished at least once. */
       public boolean rebuilt() {
           return dsl.fetchCount(ReplicaTables.REPLICA_STATE) > 0;
       }

       /** Records that every stream finished; the caller runs this in its own transaction. */
       public void markRebuilt(Instant at) {
           dsl.insertInto(ReplicaTables.REPLICA_STATE)
                   .set(ReplicaTables.REPLICA_STATE_ID, (short) 1)
                   .set(ReplicaTables.REPLICA_STATE_REBUILT_AT, at.atOffset(ZoneOffset.UTC))
                   .onConflict(ReplicaTables.REPLICA_STATE_ID)
                   .doUpdate()
                   .set(ReplicaTables.REPLICA_STATE_REBUILT_AT, at.atOffset(ZoneOffset.UTC))
                   .execute();
       }
   }
   ```

4. Sửa `ReplicaBootstrap`:
   - Thêm tham số `ReplicaState state` và `Clock clock` vào constructor.
   - `needsRebuild()` → `!state.rebuilt() || tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(writer::tenantsMissing))`.

     Giữ nguyên `tenantsMissing()` làm điều kiện phụ: nó bảo vệ trường hợp marker có nhưng dữ liệu bị xoá tay.
   - Trong `rebuild()`, sau vòng `for (ReplicaStream stream : ...)` và trước `LOG.info("replica rebuild applied ...")`:

     ```java
     tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(() -> {
         state.markRebuilt(clock.now());
         return null;
     }));
     ```

     Marker phải nằm trong transaction riêng, sau khi mọi page đã commit. Không đặt trong `applyPage`.

5. Cập nhật javadoc `ReplicaBootstrap`: nói rõ guard là marker + tenants rỗng, và tại sao đếm row theo stream không đủ.

6. Migration chỉ cho Billing: tạo `billing/apps/app-boot/src/main/resources/db/migration/V26__replica_state.sql` với nội dung DDL ở bước 1, kèm comment nêu lý do.

   **Platform không cần migration này — đã kiểm chứng:** `grep -rn "ReplicaBootstrap\|ReplicaProperties\|SnapshotClient" platform/apps/app-boot/src/main/java/` trả về **rỗng**; Platform chỉ là producer trên `platform.events` và là server của snapshot API. `ReplicaHarness` ở `platform/apps/app-boot/src/test/java/dev/ngb/platform/support/ReplicaHarness.java` chỉ là harness test tạo schema tạm để chạy `ReplicaWriter`, không phải replica production. Dependency `dev.ngb:core-replica` trong `platform/apps/app-boot/build.gradle.kts:12` tồn tại vì harness đó. Không thêm migration cho Platform.

7. Wire bean: thêm `ReplicaState` bean vào `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/config/ReplicaConfiguration.java` và truyền vào `ReplicaBootstrap`. `Clock` đã có bean ở đó.

8. Mở rộng `ReplicaRebuildIntegrationTest` (đã có sẵn hạ tầng — **không** tạo test mới). Ba ca, mỗi ca thêm vào cùng file:

   - **Ca bug (quan trọng nhất):** seed snapshot, làm `RecordingTransport` ném khi paging stream thứ hai (`tenant-domains`), gọi `bootstrap.rebuild()` và assert nó ném; assert `rowCount("tenants") == 3` (**đã ghi**) và `rowCount("tenant_domains") == 0`; assert `bootstrap.needsRebuild()` vẫn `true`. Trên code cũ ca này **fail** (`needsRebuild()` trả `false` vì `tenants` đã có). Đây là bằng chứng thực thi của P0-3.
   - **Ca hội tụ:** tiếp ca bug với transport hoạt động lại, gọi `bootstrap.rebuild()` lần hai; assert cả năm bảng đầy đủ và `replica_state.rebuilt_at` khác null.
   - **Ca không rebuild thừa:** sau rebuild thành công, `transport.reset()`, assert `needsRebuild()` là `false` và `transport.calls` rỗng (đã có ca tương tự `aLoadedReplicaIsNotRebuilt`; ca này chỉ cần giữ xanh).

   `setUp()` hiện `drop table if exists user_grants, app_entitlements, tenant_systems, tenant_domains, tenants` — **phải thêm `replica_state`** vào danh sách drop, nếu không marker của test trước rò sang test sau.

9. Quyết định về điều kiện phụ `tenantsMissing()`: **giữ nó**, và ghi rõ giới hạn. Nó chỉ nhìn bảng `tenants`, nên marker có + `tenant_domains` bị xoá tay sẽ **không** kích hoạt rebuild. Lý do chấp nhận: một lần restore/mất dữ liệu từng phần là sự cố vận hành cần xử lý tường minh (xoá `replica_state` để ép rebuild), không phải thứ guard tự đoán. Không thêm consistent-hash hay count-per-stream: chi phí đọc toàn bộ replica mỗi lần boot lớn hơn lợi ích, và chưa có ca thực tế.

   Ghi giới hạn này vào Risks và vào javadoc `ReplicaBootstrap`, để người vận hành biết cách ép rebuild: `delete from replica_state;`.

9b. Thêm ca test cho hành vi mong đợi: sau rebuild thành công, xoá hết `tenants` → `needsRebuild()` trả `true` (điều kiện phụ hoạt động); xoá chỉ `tenant_domains` → `needsRebuild()` trả `false` (giới hạn đã biết). Test thứ hai ghi lại hành vi, không phải kỳ vọng lý tưởng — đặt tên nêu rõ điều đó.

10. **Kiểm chứng bug có thật trước khi sửa**: chạy ca bug ở bước 8 với code chưa sửa, xác nhận `needsRebuild()` trả `false` (fail), ghi lại output. Sau khi sửa, chạy lại, xác nhận pass.

## Verification

```bash
# 1. Rebuild test: ca bug, ca hội tụ, ca không rebuild thừa
cd core && ./gradlew :core-platform-sync:test --tests '*ReplicaRebuildIntegrationTest*'

# 2. Core build, gồm ReplicaBoundaryTest, PlatformSyncBoundaryTest và ReplicaTablesContractTest
cd core && ./gradlew build

# 3. Contract bảng mới: ReplicaTablesContractTest gọi assertComplete()
cd core && ./gradlew :core-replica:test --tests '*ReplicaTablesContractTest*'

# 4. Migration mới chạy được và Jooq schema không drift
cd billing && ./gradlew :apps:app-boot:test --tests '*JooqSchemaTest*'

# 5. Billing và Platform vẫn build
cd billing && ./gradlew build
cd platform && ./gradlew build
```

`ReplicaTablesContractTest` tự bắt bảng `replica_state` nếu `replica-tables.sql` và migration của Billing lệch nhau — không cần viết test schema riêng.

## Verification evidence (đã chạy)

- `cd core && ./gradlew :core-platform-sync:test --tests '*ReplicaRebuildIntegrationTest*'` — **5/5 PASSED**: ca bug,
  ca hội tụ, ca "không rebuild thừa", `aRebuiltReplicaWithLostTenantRowsIsRebuiltAgain` và ca sẵn có.
- **Bug có thật, trước khi sửa:** tạm khôi phục `needsRebuild()` cũ (chỉ `writer.tenantsMissing()`) rồi chạy lại ca bug,
  kết quả `FAILED — Expecting value to be true but was false` ở assert `bootstrap.needsRebuild()`. Đúng như plan dự
  đoán: sau khi stream đầu đã ghi, guard cũ coi replica là xong. Khôi phục bản sửa: ca **PASSED**.
- `cd core && ./gradlew build` — **BUILD SUCCESSFUL**, gồm `ReplicaBoundaryTest`, `PlatformSyncBoundaryTest`,
  `ReplicaTablesContractTest` (gọi `assertComplete()` nên bảng mới được kiểm).
- `cd billing && ./gradlew :apps:app-boot:test --tests '*JooqSchemaTest*' -Djooq.update=true` — chạy lại sinh schema;
  sau đó `cd billing && ./gradlew build` — **BUILD SUCCESSFUL**, gồm `JooqSchemaTest` không drift.
- Migration mới: `billing/apps/app-boot/src/main/resources/db/migration/V26__replica_state.sql`. Schema jOOQ sinh lại
  thêm `tables/ReplicaState.java` và `tables/records/ReplicaStateRecord.java`.
- `cd platform && ./gradlew build` — **BUILD SUCCESSFUL** (Platform không cần migration, đúng bước 6).
- Docs: cập nhật `docs/suite-messaging.md` mục "Rebuild from zero" để mô tả marker và cách ép rebuild.

## Success criteria

- [x] `replica_state` có trong `replica-tables.sql` (một nguồn DDL) và trong `ReplicaTables`.
- [x] `ReplicaBootstrap.needsRebuild()` dùng marker, không chỉ `tenantsMissing()`.
- [x] Marker chỉ được ghi sau khi mọi stream xong (ca bug chứng minh `rebuilt_at` vẫn null khi rebuild ném).
- [x] Ca bug fail trên code cũ (`needsRebuild()` trả `false`), pass trên code mới.
- [x] Ca hội tụ: rebuild đứt rồi chạy lại → đầy đủ cả năm stream.
- [x] Giới hạn "xoá chỉ `tenant_domains` không kích hoạt rebuild" có test ghi lại và có trong javadoc + Risks.
- [x] Platform **không** cần migration `replica_state`, kèm lý do đọc từ code (ghi trong bước 6).
- [x] `cd core && ./gradlew build`, `cd billing && ./gradlew build`, `cd platform && ./gradlew build` đều xanh.

## Risks

- **Bảng mới trong DDL dùng chung.** Mọi app tiêu thụ replica phải có `replica_state`, nếu không `ReplicaState.rebuilt()` sẽ fail lúc runtime. Mitigation: thêm vào `replica-tables.sql` để `ReplicaSchemaAssertions.assertComplete()` bắt app nào thiếu (nó đọc chính resource đó), và migration ở bước 6 phải land trong cùng PR.
- **Marker ghi được nhưng dữ liệu chưa thật sự đầy đủ** nếu một stream trả 0 page do lỗi im lặng. `SnapshotClient.fetchPages` chỉ trả khi `next == null`, nên 0 page là "Platform nói stream rỗng" — chấp nhận được. Nếu Platform trả 200 với body rỗng sai, đó là lỗi của Platform, không phải của guard này.
- **`tenantsMissing()` giữ lại có thể gây rebuild thừa** khi mọi tenant bị deactivate? Không: nó đếm theo `code != 'platform'`, không lọc status. Đã kiểm (`ReplicaWriter.java:241-249`).
- **Marker không giúp phát hiện replica bị lệch sau khi rebuild xong** (ví dụ event bị mất). Đó là bài toán khác — replica lag metric — và nằm ngoài scope, đã ghi ở Follow-up của `plan.md`.

## Security considerations

`replica_state` chứa đúng một timestamp, không có dữ liệu tenant, không có PII. Row-level security không áp dụng: bảng này là metadata của replica toàn cục, giống cách rebuild đang chạy ở `RequestScope.platform()`. Grant cho app role giống các bảng replica khác.

## Next steps

Không phase nào phụ thuộc phase này. Đã land thì nên cập nhật `docs/suite-messaging.md` mục "Rebuild from zero" để nêu marker — docs hiện mô tả điều kiện cũ.
