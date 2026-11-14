---
phase: 4
title: "Compensation Keycloak không che lỗi"
status: completed
priority: P2
effort: 4h
dependencies: [2]
---

# Phase 4: Compensation Keycloak không che lỗi

## Goal

Hai chỗ: compensation không che exception gốc, và một identity đã tồn tại ở Keycloak được **adopt** thay vì làm retry chết vĩnh viễn với 409.

## Findings addressed

- `CreateUserUseCase.java:52-58`:
  ```java
  try { return persist(actor, requestId, command, subject); }
  catch (RuntimeException e) { deleteIdentity(subject); throw e; }
  ```
  Nếu `deleteIdentity` ném (Keycloak không tải được lúc compensate), exception gốc bị thay thế: mất thông tin vì sao `persist` fail.
- `ProvisionTenantUseCase.java:64-71` — cùng pattern, cùng vấn đề.
- `CreateUserUseCase.java:91-93` — `deleteIdentity` gọi thẳng `identities.deleteUser(...)`, không bọc.
- `KeycloakIdentityProvider.java:38-53` — `createUser` dùng `.retrieve().toBodilessEntity()`; một 409 từ Keycloak ném `RestClientResponseException` (hoặc subclass) chưa được đọc. Không có lookup theo username, không map sang lỗi nghiệp vụ.
- `KeycloakIdentityProvider.java:56-63` — `deleteUser` cũng không bắt 404; xoá một identity đã biến mất sẽ ném.
- `AccessConfiguration.java:74-81` — bean `IdentityProviderPort` được tạo trực tiếp, không có decorator.

## Key insight

Hai lỗi tách biệt, sửa riêng:

1. **Compensation không được che lỗi gốc.** Đây là nguyên tắc: `addSuppressed` giữ cả hai, và lỗi gốc là thứ người vận hành cần thấy trước.
2. **Identity mồ côi phải adopt được.** Username `{tenantCode}:{email}` là tất định, nên khi Keycloak nói 409, identity đó *chính là* identity ta muốn. Tra theo username và dùng id của nó là hành vi đúng, không phải workaround.

Điều 2 quan trọng vì nó biến retry từ "chết vĩnh viễn" thành "tự hội tụ". Không có nó, một lần Keycloak lỗi tạm thời khoá email đó vĩnh viễn cho tới khi có người xoá tay.

**Không** thêm job đối chiếu Keycloak ↔ Platform ở phase này. Adopt-on-409 giải đúng bài toán với ít bề mặt hơn, và một job đối chiếu cần pagination toàn bộ user của realm — chi phí lớn hơn nhiều so với lợi ích khi chưa có ca thực tế.

## Files to modify

- Modify `platform/contexts/access/src/application/java/dev/ngb/platform/access/application/user/command/CreateUserUseCase.java` — compensation dùng `addSuppressed`; không ném từ `catch`.
- Modify `platform/contexts/access/src/application/java/dev/ngb/platform/access/application/tenant/command/ProvisionTenantUseCase.java` — cùng sửa.
- Modify `platform/contexts/access/src/adapter/java/dev/ngb/platform/access/adapter/auth/keycloak/KeycloakIdentityProvider.java` — adopt-on-409, delete chịu 404.
- Create `platform/contexts/access/src/test/java/dev/ngb/platform/access/adapter/auth/keycloak/KeycloakIdentityProviderTest.java` — nếu module `adapter` có test source set; nếu không, đặt ở `app-boot` và dùng `MockRestServiceServer` hoặc một HTTP server nội bộ.
- Modify `platform/apps/app-boot/src/test/java/dev/ngb/platform/access/CreateUserCompensationIntegrationTest.java` — thêm ca compensation fail.

## Implementation steps

1. Trong `CreateUserUseCase.execute`, đổi `catch` thành:

   ```java
   } catch (RuntimeException e) {
       try {
           deleteIdentity(subject);
       } catch (RuntimeException compensation) {
           e.addSuppressed(compensation);
       }
       throw e;
   }
   ```

   Lỗi gốc luôn là thứ được ném; lỗi compensation đi kèm dưới dạng suppressed. Ghi một `LOG.warn` khi compensation fail, nêu rõ identity nào còn mồ côi để người vận hành biết cần xoá tay.

2. Cùng sửa đó ở `ProvisionTenantUseCase.execute` (dòng 64-71). Hai chỗ, cùng hình dạng — không tạo helper dùng chung vì chúng sống trong hai use case khác context và chỉ hai dòng.

3. Trong `KeycloakIdentityProvider.createUser`, bọc lời gọi để nhận 409 riêng. Dùng `RestClient` của Spring, nên cách gọn nhất là thêm `.onStatus(...)`:

   ```java
   .retrieve()
   .onStatus(status -> status.value() == 409, (request, response) -> { /* handled below */ })
   ```

   Cấu trúc đơn giản hơn: gọi trong `try`, bắt `RestClientResponseException e` khi `e.getStatusCode().value() == 409`, rồi `return subjectOf(username)`. Mọi status khác được ném lại nguyên vẹn.

   `subjectOf(username)` là một lời gọi mới `GET /admin/realms/{realm}/users?username={username}&exact=true`, đọc phần tử đầu, lấy `id`. Nếu không tìm thấy, ném `IllegalStateException` với thông điệp nêu rõ Keycloak báo trùng nhưng không tra được — người vận hành cần biết.

4. Trong `deleteUser`, chịu 404 như no-op: bắt `RestClientResponseException` với `getStatusCode().value() == 404` và trả về bình thường. Điều này làm compensation an toàn khi identity đã bị xoá — đúng ngữ nghĩa "ensure absent".

5. Ghi rõ trong javadoc `IdentityProviderPort.createUser`: *"creating a username that already exists returns the existing subject instead of failing, because the username is deterministic per (tenant, email) and a retry after a failed compensation must converge."* Ràng buộc này là hợp đồng của port, không phải chi tiết của Keycloak.

6. Test ở `CreateUserCompensationIntegrationTest` (đã có fixture `RecordingIdentities` + `FailingUserRepository`):
   - Ca mới: `RecordingIdentities` được cấu hình để `deleteUser` ném. Gọi `createUser.execute(...)` và assert:
     - exception ném ra là `IllegalStateException` với message `"the user insert failed"` (lỗi gốc, không bị thay thế).
     - exception đó có `getSuppressed()` đúng một phần tử, là lỗi compensation.
     - không có user row nào sót.
   - Ca cũ (`aFailedWriteRemovesTheIdentityItCreated`) phải vẫn xanh.

7. Test cho adopt-on-409. Nếu module `adapter` không có test source set, đặt ở `app-boot` với một `HttpServer` (JDK) trả 409 cho POST và một danh sách cho GET, truyền `baseUrl` của nó vào `KeycloakIdentityProvider`. Assert `createUser` trả đúng id có sẵn. Đây là test không cần Keycloak thật, nên chạy nhanh và không flaky.

8. **Kiểm chứng:** với adopt-on-409, viết một ca chứng minh retry hội tụ — giả lập compensation fail ở lần một (identity còn lại), rồi chạy lại `createUser.execute(...)` với cùng email; assert lần hai thành công và dùng đúng subject cũ. Chạy ca này trên code **chưa sửa** phải fail (409 vô chủ), trên code đã sửa phải pass.

## Verification

```bash
# 1. Compensation giữ lỗi gốc
cd platform && ./gradlew :apps:app-boot:test --tests '*CreateUserCompensation*'

# 2. Adopt-on-409 (đặt tên test rõ ràng để lệnh này bắt được)
cd platform && ./gradlew :apps:app-boot:test --tests '*KeycloakIdentity*'

# 3. Provisioning vẫn atomic
cd platform && ./gradlew :apps:app-boot:test --tests '*ProvisionTenantAtomicity*'

# 4. Bootstrap repair của phase 2 không bị ảnh hưởng
cd platform && ./gradlew :apps:app-boot:test --tests '*BootstrapAdminRepair*'

# 5. Toàn service
cd platform && ./gradlew build
```

## Verification evidence (đã chạy)

- `cd platform && ./gradlew :apps:app-boot:test --tests '*CreateUserCompensation*'` — **2/2 PASSED**, gồm ca cũ
  `aFailedWriteRemovesTheIdentityItCreated` và ca mới `aFailedCompensationDoesNotReplaceTheOriginalFailure`.
- `cd platform && ./gradlew :contexts:access:test --tests '*KeycloakIdentity*'` — **4/4 PASSED**. Test đặt ở module
  `contexts:access` (adapter test source set thấy được lớp adapter) với một `HttpServer` của JDK, không cần Keycloak thật.
- **Bug có thật, trước khi sửa:** stash riêng `KeycloakIdentityProvider.java` + `CreateUserUseCase.java` rồi chạy lại:
  - `aDuplicateUsernameReturnsTheExistingSubject` — **FAILED** (409 không được adopt).
  - `deletingAnIdentityThatIsAlreadyGoneIsNotAnError` — **FAILED** (404 bị ném).
  - `aFailedCompensationDoesNotReplaceTheOriginalFailure` — **FAILED** (lỗi gốc bị thay thế).
  Pop bản sửa lại: cả ba **PASSED**.
- `cd platform && ./gradlew :apps:app-boot:test --tests '*ProvisionTenantAtomicity*' --tests '*BootstrapAdminRepair*'`
  — **xanh**.
- `cd platform && ./gradlew build` — **BUILD SUCCESSFUL**.
- `IdentityProviderPort` javadoc ghi hợp đồng adopt-on-exists và "delete an absent identity is not an error".

## Success criteria

- [x] `CreateUserUseCase` và `ProvisionTenantUseCase` dùng `addSuppressed`; lỗi gốc không bao giờ bị thay thế.
- [x] Test compensation-fail: exception gốc là lỗi insert, phần suppressed là lỗi Keycloak.
- [x] `createUser` trả subject có sẵn khi Keycloak 409, không ném.
- [x] `deleteUser` coi 404 là thành công.
- [x] Test retry-hội-tụ fail trên code cũ, pass trên code mới.
- [x] Javadoc `IdentityProviderPort.createUser` ghi hợp đồng adopt-on-exists.
- [x] `cd platform && ./gradlew build` xanh.

## Risks

- **Adopt-on-409 có thể adopt nhầm một identity của người khác** nếu cùng username. Trong mô hình `{tenantCode}:{email}`, username là duy nhất theo nghĩa nghiệp vụ, nên đây là giả định đúng — nhưng phải ghi rõ. Đây là trade-off đã được chấp nhận, ghi trong plan.
- **`?username=X&exact=true` trả mảng, không phải một object.** Phải kiểm tra kích thước mảng > 0 trước khi lấy phần tử đầu, và nếu > 1 thì đó là bất thường cần nêu rõ trong message.
- **Thêm một lời gọi HTTP trong đường compensation.** Chỉ xảy ra khi 409, tức khi đã có sự cố. Không nằm trong đường nóng.
- **Không sửa được identity mồ côi đã tồn tại từ trước** nếu Platform đã có user row trỏ tới nó. Trường hợp đó không cần adopt (user đã có), nên không phải vấn đề. Nhưng identity mồ côi **không** có user row thì giờ tự hội tụ nhờ adopt.

## Security considerations

- Adopt-on-409 dựa vào username tất định; không dùng email làm khoá tra cứu (email có thể trùng giữa các tenant trong realm). Đúng như `docs/authentication-and-authorization.md` mô tả.
- Không log temporary password hay admin client secret. `KeycloakIdentityProvider.adminToken()` hiện lấy token mỗi lần gọi — **không** thuộc scope phase này, nhưng khi thêm lời gọi lookup, tổng số lời gọi token tăng; ghi chú lại như tech debt, không sửa ở đây.
- Lỗi compensation được log với subject, không phải email hay password. Subject là UUID, không phải PII trực tiếp.

## Next steps

Sau phase này, cân nhắc cache admin token trong `KeycloakIdentityProvider` (đang lấy mới mỗi lời gọi). Không làm ở đây vì nó là tối ưu hoá riêng, không phải partial-state recovery.
