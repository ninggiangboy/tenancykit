---
phase: 5
title: "Tách HTTP ra khỏi transaction ở tenant"
status: completed
priority: P2
effort: 5h
dependencies: [1]
---

# Phase 5: Tách HTTP ra khỏi transaction ở tenant

## Goal

`ManageOrderUseCase.cancel` và `ManageWalletSettingUseCase.update` không còn giữ transaction DB trong lúc gọi Billing, theo đúng nguyên tắc repo đã dùng ở `CreateOrderUseCase`.

## Findings addressed

- `ManageOrderUseCase.java:35-51` — toàn bộ thân nằm trong `unitOfWork.transactional(...)`; bên trong gọi `subscriptions.cancel(order.subscriptionId())`.
- `ManageWalletSettingUseCase.java:74-82` — `unitOfWork.transactional(...)` bọc `wallets.open(...)` và `settings.save(setting)`.
- Đối chiếu: `CreateOrderUseCase.java:42-45` ghi rõ trong javadoc *"The remote call stays outside the final transaction"* và tổ chức thành `persist(pending)` → HTTP → `commit(...)`, tức pattern đúng đã tồn tại trong cùng module.

## Key insight

Đây là vi phạm nguyên tắc, **không** phải lỗ hổng mất tiền, nên priority là P2. Lý do chúng tự lành khi retry:

- `CancelSubscriptionUseCase.java:45-56` — cancel một subscription đã cancel trả `UNCHANGED`, không ném. Nên retry an toàn.
- `ManageWalletSettingUseCase` dùng `IdempotencyKeys.wallet(customerId, currency)` (`ManageWalletSettingUseCase.java:76`), tất định, nên `wallets.open` trả cùng wallet khi gọi lại.

Nhưng hai hệ quả vẫn thật và đáng sửa:

1. Khi Billing chậm, transaction giữ connection và row lock suốt thời gian chờ — dưới tải có thể cạn pool.
2. Transaction local rollback trong khi Billing đã commit để lại trạng thái hai bên lệch nhau cho tới khi có người gọi lại.

Với `cancel`, trạng thái lệch có thể quan sát được: Billing đã `CANCELED` nhưng order còn `RUNNING`/`QUEUED`. Phase này đóng khoảng đó bằng **trạng thái trung gian tường minh**, không bằng cách giả vờ atomic.

## Files to modify

- Modify `tenant/modules/resource/src/domain/java/dev/ngb/tenant/resource/domain/order/Order.java` — thêm trạng thái `CANCELING` và các cạnh chuyển tiếp.
- Modify `tenant/modules/resource/src/api/java/dev/ngb/tenant/resource/api/order/OrderStatus.java` — thêm `CANCELING`.
- Modify `tenant/modules/resource/src/application/java/dev/ngb/tenant/resource/application/order/command/ManageOrderUseCase.java` — hai transaction, HTTP ở giữa.
- Modify `tenant/modules/customer/src/application/java/dev/ngb/tenant/customer/application/wallet/command/ManageWalletSettingUseCase.java` — HTTP ra ngoài transaction.
- Modify `tenant/modules/resource/src/application/java/dev/ngb/tenant/resource/application/order/command/CreateOrderUseCase.java` — mở rộng `expirePending()` để dọn `CANCELING` treo.
- Modify test tương ứng: `ManageOrderUseCaseTest`, `ManageWalletSettingUseCaseTest`, `OrderTest`.
- Create `tenant/apps/app-boot/src/test/java/dev/ngb/tenant/resource/OrderCancelPartialStateIntegrationTest.java`.

## Implementation steps

### `ManageOrderUseCase.cancel`

1. Thêm `CANCELING` vào `OrderStatus`. Trong `Order.transitions()` (`Order.java:196-212`):
   - `QUEUED` → thêm `CANCELING`
   - `RUNNING` → thêm `CANCELING`
   - `SUSPENDED` → thêm `CANCELING`
   - `PENDING` → **không** thêm: order chưa `PENDING` chưa có subscription, nên cancel trực tiếp sang `CANCELED` như hiện tại.
   - `CANCELING` → `CANCELED` (khi Billing xong), `CANCELING` → `FAILED` (khi hết lượt thử).
   - `CANCELING` là **không** terminal, nên `terminal()` phải tiếp tục trả `false` cho nó — kiểm tra `terminal()` dựa trên tập rỗng, và `CANCELING` có hai cạnh đi ra nên tự đúng.

2. Thêm factory `Order canceling(Instant now)` và `Order canceledAfterBilling(Instant now)` (hoặc tái dùng `canceled`), theo khuôn `queued`/`running` đang có, dùng `requireFrom` để giữ bất biến.

3. Viết lại `cancel(...)` thành ba bước:

   ```java
   // TX 1: chuyển sang CANCELING và đọc dữ liệu cần cho Billing
   Order pending = unitOfWork.transactional(() -> {
       Order order = orders.findById(orderId).orElseThrow(...);
       // visibility + terminal checks giữ nguyên
       Order canceling = order.canceling(clock.now());
       orders.save(canceling);
       return canceling;
   });

   // Không transaction: gọi Billing
   try {
       if (pending.subscriptionId() != null) {
           subscriptions.cancel(pending.subscriptionId());
       }
   } catch (RuntimeException e) {
       // TX 2 (riêng): ghi lý do để người vận hành thấy, giữ CANCELING để lần sau thử lại
       unitOfWork.transactional(() -> { orders.save(pending.failed(reason(e), clock.now())); return null; });
       throw e;
   }

   // TX 2: hoàn tất
   return unitOfWork.transactional(() -> {
       Order fresh = orders.findById(orderId).orElseThrow(...);
       Order canceled = fresh.canceledAfterBilling(clock.now());
       orders.save(canceled);
       return canceled;
   });
   ```

   Điểm quan trọng: `CANCELING` được ghi **trước** khi gọi Billing, nên nếu process chết giữa chừng, trạng thái trung gian tồn tại và quan sát được.

4. Mở rộng `CreateOrderUseCase.expirePending()` (được `OrderExpiryJob` gọi mỗi phút) để dọn `CANCELING` treo quá `reservationTtl`: gọi `subscriptions.cancel(...)` lại (idempotent, trả `UNCHANGED` nếu đã xong) rồi chuyển `CANCELED`. Nếu vẫn lỗi, giữ nguyên cho lượt sau.

   Lý do dùng chung reaper thay vì job mới: `OrderExpiryJob` đã tồn tại, đã có `@Scheduled`, và cùng bất biến "order treo phải được dọn". Thêm job thứ hai là chi phí thuần.

### `ManageWalletSettingUseCase.update`

5. Đổi thành: **không** bọc `wallets.open` trong transaction.

   ```java
   Wallet wallet = wallets.open(
           IdempotencyKeys.wallet(customer.billingCustomerId(), normalized),
           new BillingWallets.OpenWallet(...));
   CustomerWalletSetting setting = new CustomerWalletSetting(..., wallet.id(), clock.now());
   return unitOfWork.transactional(() -> {
       settings.save(setting);
       return setting;
   });
   ```

   An toàn vì `wallets.open` có idempotency key tất định: nếu bước ghi setting fail, lần gọi lại dùng cùng key và nhận cùng wallet, không mở hai ví.

6. Cập nhật javadoc cả hai class: nêu rõ HTTP nằm ngoài transaction và cái gì đảm bảo retry an toàn (idempotent cancel / idempotency key của wallet).

### Tests

7. `OrderTest`: thêm ca transition cho `CANCELING` — `QUEUED→CANCELING→CANCELED` hợp lệ; `PENDING→CANCELING` **không** hợp lệ; `terminal()` trả `false` cho `CANCELING`.

8. `ManageOrderUseCaseTest`: cập nhật vì số transaction đổi. Thêm ca: Billing cancel ném → order ở `CANCELING` (không phải `CANCELED`, không phải trạng thái cũ), exception được ném.

9. `ManageWalletSettingUseCaseTest`: ca Billing `open` ném → không có setting nào được ghi (transaction chưa mở); ca bình thường → setting được ghi với đúng `wallet.id()`.

10. `OrderCancelPartialStateIntegrationTest` (dùng `HttpIntegrationTest` + `FakeBilling`): đặt `FakeBilling` để `cancel` ném ở lần một, chạy `cancel(...)`, assert order `CANCELING`; cho `FakeBilling` hoạt động lại, chạy `CreateOrderUseCase.expirePending()`, assert order `CANCELED` và Billing chỉ nhận một lần cancel **có hiệu lực**.

11. **Kiểm chứng:** chạy `OrderCancelPartialStateIntegrationTest` trên code chưa sửa — không có trạng thái `CANCELING` nào để assert, nên test không compile; thay vào đó chạy test hiện có của `ManageOrderUseCase` và ghi lại rằng nó đang assert một transaction duy nhất (số transaction = 1). Sau khi sửa, số đó là 3 ở đường thành công. Ghi cả hai con số.

## Verification

```bash
# 1. Domain transitions
cd tenant && ./gradlew :modules:resource:test --tests '*OrderTest*'

# 2. Use case tests
cd tenant && ./gradlew :modules:resource:test --tests '*ManageOrderUseCase*'
cd tenant && ./gradlew :modules:customer:test --tests '*ManageWalletSettingUseCase*'

# 3. Tích hợp: cancel dở dang rồi tự dọn
cd tenant && ./gradlew :apps:app-boot:test --tests '*OrderCancelPartialState*'

# 4. HTTP không còn nằm trong transaction
grep -n "unitOfWork.transactional" tenant/modules/resource/src/application/java/dev/ngb/tenant/resource/application/order/command/ManageOrderUseCase.java
# → 3 hit: TX chuyển CANCELING, TX ghi lỗi, TX hoàn tất; không hit nào bọc subscriptions.cancel

# 5. Toàn service, gồm ArchUnit
cd tenant && ./gradlew build
```

## Verification evidence (đã chạy)

- `cd tenant && ./gradlew :modules:resource:test` — **xanh** (gồm `OrderTest` với các ca `CANCELING`, và
  `ManageOrderUseCaseTest`).
- `cd tenant && ./gradlew :modules:customer:test` — **xanh**, gồm hai ca mới
  `theWalletIsOpenedOutsideTheTransactionThatSavesTheSetting` và `aSaveThatFailsAfterBillingOpenedTheWalletWritesNoSetting`.
- `cd tenant && ./gradlew :apps:app-boot:test --tests '*OrderCancelPartialState*'` — **2/2 PASSED**:
  Billing từ chối cancel → order `CANCELING` (có `failureReason`), reaper đưa về `CANCELED` và Billing nhận `sub_1`;
  đường thành công → `CANCELED`.
- **Số transaction (bước 11 của plan):** trước khi sửa `ManageOrderUseCaseTest` assert
  `unitOfWork.transactions == 1` (một transaction duy nhất, HTTP nằm trong đó). Sau khi sửa, đường thành công là
  **2** transaction (ghi `CANCELING`, rồi đóng `CANCELED`; transaction thứ ba chỉ chạy khi remote call ném). Test
  khẳng định thêm `cancelCallsWhileTransactionOpen == 0`.
- `grep -n "unitOfWork.transactional" ManageOrderUseCase.java` → 3 hit (ghi `CANCELING`, ghi lỗi, đóng `CANCELED`);
  không hit nào bọc `subscriptions.cancel`.
- `cd tenant && ./gradlew build` — **BUILD SUCCESSFUL**, gồm ArchUnit và Modulith.
- Docs: `docs/tenant-system.md` thêm quy tắc "không transaction nào bọc lời gọi Billing".

## Success criteria

- [x] `OrderStatus.CANCELING` tồn tại và là trạng thái không terminal.
- [x] `ManageOrderUseCase.cancel` ghi `CANCELING` trước khi gọi Billing; không transaction nào bọc lời gọi HTTP.
- [x] `expirePending()` dọn được order `CANCELING` treo và retry cancel một cách idempotent.
- [x] `ManageWalletSettingUseCase.update` gọi `wallets.open` ngoài transaction.
- [x] Test: Billing lỗi → order `CANCELING` (không mất trạng thái), rồi reaper đưa về `CANCELED`.
- [x] `cd tenant && ./gradlew build` xanh, gồm ArchUnit và Modulith.

## Risks

- **Thêm một trạng thái vào lifecycle là thay đổi domain thật.** API trả `OrderStatus` ra ngoài, nên client phải xử lý `CANCELING`. Mitigation: `CANCELING` là tạm thời và rất ngắn; nếu chưa muốn lộ ra API, có thể map nó thành `QUEUED` trong tầng web — nhưng như vậy che mất sự thật. Đề xuất: **lộ ra**, và ghi vào OpenAPI của tenant nếu có.
- **`expirePending` giờ làm hai việc** (hết hạn PENDING, dọn CANCELING). Tên method không còn đúng hoàn toàn. Mitigation: đổi tên thành `reapStaleOrder()` và cập nhật `OrderExpiryJob`; hoặc giữ tên và ghi rõ trong javadoc. Chọn cách thứ hai nếu muốn giảm churn.
- **Ba transaction thay vì một** làm tăng số round-trip DB. Không đáng kể: hai transaction đầu rất ngắn.
- **`subscriptions.cancel` idempotent là giả định đã kiểm chứng** ở `CancelSubscriptionUseCase.java:54-56` (`UNCHANGED`). Nếu Billing đổi để ném khi cancel lần hai, reaper sẽ lặp mãi. Mitigation: test tích hợp assert lần cancel thứ hai không ném.

## Security considerations

- `CANCELING` không thay đổi quyền: `cancel(...)` vẫn kiểm tra `administrator` và `customerId` như hiện tại, và kiểm tra đó nằm trong TX 1 trước khi bất cứ điều gì được ghi.
- Không log secret; `reason(e)` cắt 300 ký tự như `CreateOrderUseCase.reason` đang làm.
- Order ở `CANCELING` vẫn phải hiện với đúng người: truy vấn đọc dùng `customerId`, không lọc theo status, nên không rò rỉ.

## Next steps

Phase này là phase cuối. Sau khi land, cân nhắc thêm `CANCELING` vào tài liệu thiết kế của sample tenant (`docs/tenant-system.md`) để người copy reference impl hiểu vì sao có trạng thái đó.
