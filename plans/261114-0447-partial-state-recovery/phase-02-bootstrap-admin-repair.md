---
phase: 2
title: "Bootstrap admin thành repair idempotent"
status: completed
priority: P1
effort: 4h
dependencies: []
---

# Phase 2: Bootstrap admin thành repair idempotent

## Goal

`BootstrapPlatformAdminUseCase` biến từ "tạo một lần rồi thôi" thành "mỗi lần boot đảm bảo admin có role và assignment", để một lần fail giữa hai transaction không để lại admin đăng nhập được nhưng không có quyền nào.

## Findings addressed

- `BootstrapPlatformAdminUseCase.java:62-80` — thứ tự hiện tại:
  1. `createUser.execute(...)` → mở unit of work riêng, ghi user và `publishNew` ở **version 1** với permission set **rỗng** (role chưa được assign nên `access.effectivePermissions(...)` trả rỗng).
  2. `unitOfWork.transactional(...)` thứ hai → tạo role, `access.assign(...)`, `publisher.publishFor(admin.id())` bump version và publish lại với permission thật.
  
  Nếu bước 2 ném, user vẫn tồn tại (bước 1 đã commit), nhưng không role, không grant, và replica đã nhận `user-access.changed` version 1 với permission rỗng.
- `BootstrapPlatformAdminUseCase.java:63-65` — `if (users.findByEmail(platform, email).isPresent()) return;` khiến lần boot sau **bỏ qua hoàn toàn**, nên trạng thái hỏng là vĩnh viễn.
- `PlatformBootstrap.java:35-43` — gọi bootstrap mỗi lần start, nên nếu bootstrap tự sửa được thì lỗi tự lành ở lần restart kế tiếp.
- `JooqAccessRepository.java:50-57` — `assign` đã idempotent bằng `onConflict(USER_ID, ROLE_ID).doNothing()`, nên gọi lại là an toàn.
- `RoleRepository.java:26` — `findByName(UUID tenantId, String name)` đã có; `JooqRoleRepository` đã hỗ trợ.

## Key insight

Vấn đề không phải "thiếu transaction" (không thể gộp Keycloak vào transaction DB), mà là **early-return biến một lần fail tạm thời thành hỏng vĩnh viễn**. Hai điều kiện đã có sẵn để sửa rẻ:

- `assign` idempotent → gọi lại vô hại.
- `findByName` cho phép tìm role thay vì tạo trùng.

Nên repair chỉ là: **bỏ early-return, và luôn ensure (user, role, assignment, publish)** thay vì "thấy user thì thôi".

Một điểm cần cẩn thận: repair **không được** tạo identity Keycloak lần hai. Vì vậy nó phải phân biệt "user đã có" (chỉ ensure role + assignment) và "user chưa có" (tạo identity rồi persist). `createUser.execute(...)` đã tự làm cả hai và có compensation; `createUser.persist(...)` chỉ ghi DB và yêu cầu subject có sẵn. Repair chỉ cần gọi `execute` khi chưa có user row.

## Files to modify

- Modify `platform/contexts/access/src/application/java/dev/ngb/platform/access/application/user/command/BootstrapPlatformAdminUseCase.java` — bỏ early-return; tách `ensureAccess(...)` dùng chung cho cả hai đường.
- Create `platform/apps/app-boot/src/test/java/dev/ngb/platform/access/BootstrapAdminRepairIntegrationTest.java` — test fail tx2 rồi gọi lại.
- Modify `platform/apps/app-boot/src/test/java/dev/ngb/platform/access/ProvisionTenantAtomicityIntegrationTest.java` — chỉ khi cần dùng chung fixture `RecordingIdentities`; xem bước 5.

## Implementation steps

1. Trong `BootstrapPlatformAdminUseCase.execute(String email, String temporaryPassword)`:
   - Giữ guard cho input rỗng (đã có ở dòng 57-59).
   - **Xoá** `if (users.findByEmail(platform, email).isPresent()) return;`.
   - Lấy user: `User admin = users.findByEmail(platform, email).orElseGet(() -> createUser.execute(...));`

     Dùng `orElseGet` (không phải `orElse`) để `createUser.execute` chỉ chạy khi thật sự thiếu user. Nếu chạy, nó chạy trong transaction riêng của nó, đúng như hiện tại, và đã có compensation.
   - Gọi `ensureAccess(admin)` — một unit of work **duy nhất**, chứa toàn bộ phần role + assignment + publish.

2. Thêm `private void ensureAccess(User admin)`:

   ```java
   unitOfWork.transactional(() -> {
       Role role = ensureRole();
       access.assign(PLATFORM_TENANT, admin.id().value(), role.id().value());
       publisher.publishFor(admin.id());
       return null;
   });
   ```

   Giữ nguyên tenant id mà lớp đang dùng (`RequestScope.PLATFORM_TENANT_ID`) thay cho biến giả định ở trên.

3. Thêm `private Role ensureRole()` chứa logic `findByName` + `orElseGet(create)` như hiện tại (dòng 67-74). Không đổi nội dung, chỉ tách ra cho đọc được. `publishFor(admin.id())` sẽ tự lock user và bump version, nên permission thật được tính **sau** khi assignment đã ghi trong cùng transaction — đúng ý đồ ban đầu.

4. Ghi chú bằng javadoc tại `execute`: bootstrap là **repair**, không phải create-once. Nêu rõ nó an toàn khi chạy lặp vì `assign` dùng `onConflict doNothing` và `publishFor` chỉ bump version khi thật sự cần.

5. Viết `BootstrapAdminRepairIntegrationTest`:
   - Dùng `PlatformContainerConfig`, `TenantContext.callAs(RequestScope.platform(), ...)`.
   - Kịch bản A (đường hỏng): cấu hình một `AccessRepository` bọc ngoài, ném `IllegalStateException` khi `assign` được gọi — mô phỏng tx2 fail. Gọi `execute("admin@example.test", "change-me")`. Assert ném. Assert `users` có row (bước 1 đã commit, không rollback được) và **không** có grant nào (query `role_assignments`).
   - Kịch bản B (tự lành): với cùng email, gọi `execute(...)` lần nữa với `AccessRepository` bình thường. Assert không ném, có đúng một grant, và user có permission không rỗng.
   - Kịch bản C (idempotent): gọi `execute(...)` lần thứ ba. Assert vẫn đúng một role tên `Platform Administrator`, đúng một grant, và không tạo user mới.
   - Dùng `FailingUserRepository`/`RecordingIdentities` hiện có nếu phù hợp, nhưng **không** sửa `ProvisionTenantAtomicityIntegrationTest` trừ khi bắt buộc — hai test độc lập nhau.

6. Kiểm chứng rằng repair không tạo identity Keycloak lần hai: trong kịch bản B, assert `identities.created()` có đúng một entry cho email đó (nếu fixture ghi nhận `createUser`; nếu chỉ ghi `deleted`, thêm ghi nhận tương ứng).

## Verification

```bash
# 1. Test mới: repair tự lành
cd platform && ./gradlew :apps:app-boot:test --tests '*BootstrapAdminRepair*'

# 2. Không còn early-return
grep -n "findByEmail" platform/contexts/access/src/application/java/dev/ngb/platform/access/application/user/command/BootstrapPlatformAdminUseCase.java
# → đúng 1 hit, nằm trong orElseGet

# 3. Test cũ vẫn xanh
cd platform && ./gradlew :apps:app-boot:test --tests '*ProvisionTenantAtomicity*' --tests '*CreateUserCompensation*'

# 4. Toàn service
cd platform && ./gradlew build
```

## Verification evidence (đã chạy)

- `cd platform && ./gradlew :apps:app-boot:test --tests '*BootstrapAdminRepair*'` — **PASSED**.
- **Bug có thật, trước khi sửa:** stash riêng `BootstrapPlatformAdminUseCase.java` rồi chạy lại ca repair, kết quả
  `FAILED — dev.ngb.core.error.ConflictException: a user with this email already exists`. Nghĩa là boot thứ hai không
  repair được mà còn ném, vì guard cũ đọc user bằng một truy vấn **ngoài transaction**.
- Pop bản sửa lại: cùng ca **PASSED**.
- **Phát hiện thêm ngoài plan:** guard cũ `users.findByEmail(...)` chạy ngoài `UnitOfWork`, mà row-level security lấy
  tenant từ transaction (`set_config('app.tenant_id', …, true)` chỉ có hiệu lực trong transaction), nên truy vấn đó
  luôn thấy rỗng. Vì vậy repair phải đọc user **trong** một transaction; đó là lý do `execute(...)` dùng
  `unitOfWork.transactional(() -> users.findByEmail(...))`.
- `grep -n "findByEmail" BootstrapPlatformAdminUseCase.java` → đúng 1 hit, nằm trong transaction của `execute`.
- `cd platform && ./gradlew :apps:app-boot:test --tests '*ProvisionTenantAtomicity*' --tests '*CreateUserCompensation*'`
  — **xanh**.
- `cd platform && ./gradlew build` — **BUILD SUCCESSFUL**.
- Test ca C (idempotent) khẳng định gọi lần ba vẫn đúng một role `Platform Administrator`, một grant, một identity.

## Success criteria

- [x] Không còn `return` sớm khi user đã tồn tại.
- [x] Role, assignment và publish nằm trong **một** unit of work.
- [x] Test kịch bản A: fail tx2 → user có row, không grant.
- [x] Test kịch bản B: gọi lại → có grant, permission không rỗng, không ném.
- [x] Test kịch bản C: gọi lần ba → vẫn một role, một grant, một identity.
- [x] `cd platform && ./gradlew build` xanh.

## Risks

- **`publishFor(admin.id())` chạy mỗi lần boot**, bump version dù permission không đổi. Điều này làm replica nhận một event thừa mỗi lần restart. Chấp nhận được: version tăng vô hại, replica ghi đè cùng dữ liệu. Nếu muốn tránh, cần so sánh permission trước/sau — nhưng đó là tối ưu hoá không cần thiết ở phase này and adds a read-before-write race. Giữ đơn giản.
- **Repair dựa vào `assign` idempotent.** Nếu ai đổi `JooqAccessRepository.assign` bỏ `onConflict`, repair sẽ ném ở lần boot thứ hai. Mitigation: thêm comment tại `assign` nêu rằng bootstrap phụ thuộc tính idempotent này, và test kịch bản C là test khoá nó.
- **Không sửa được admin đã hỏng nhưng lần boot sau không chạy lại bootstrap** vì env đã bị bỏ. Nếu `platform.bootstrap.platform-admin-email` không còn được set, repair không chạy. Đây là giới hạn chấp nhận: cần cấu hình để sửa cấu hình.

## Security considerations

- Admin platform-scope là tài khoản quyền cao nhất; repair không được cấp thêm quyền ngoài `catalog.codesValidIn(PermissionScope.PLATFORM)`. Logic đó giữ nguyên, chỉ đổi thứ tự gọi.
- Không log email/temporary password. Test dùng email giả `@example.test`.
- `assign` giữ nguyên `PLATFORM_ACTOR` là actor thực hiện, không đổi sang actor của request để tránh leo thang quyền.

## Next steps

Phase 4 sửa compensation ở `CreateUserUseCase` và `KeycloakIdentityProvider.createUser`, cùng module `access`, nên phải land sau phase này.
