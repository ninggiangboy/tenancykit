---
phase: 1
title: "Settle không gọi release thừa"
status: completed
priority: P1
effort: 3h
dependencies: []
---

# Phase 1: Settle không gọi release thừa

## Goal

`ExecuteOrdersUseCase.settle()` ngừng gọi `release()` trên reservation mà nó vừa `capture`, để một order có reservation kết thúc `COMPLETED` thay vì `FAILED` trong khi tiền đã bị thu.

## Findings addressed

- `ExecuteOrdersUseCase.java:110-127` — `release(order)` được gọi ở cả nhánh thành công và nhánh `FAILED`.
- `ChargeService.java:60-71` — khi `request.reservation() != null`, `charge` **luôn** đi vào `reservation.capture(...)` và luôn trả `SUCCEEDED`; nhánh `Charge.failed(...)` chỉ nằm trong `else if (!wallet.canSpend(amount))`, tức chỉ khi **không** có reservation.
- `Reservation.java:88-99` — `capture` làm `wallet.unhold(amount)` (nhả **toàn bộ** hold) rồi `close(CAPTURED)`. Phần chênh lệch giữa hold và `charged` đã được trả lại ngay tại đây.
- `Reservation.java:101-105` — `release` gọi `requireActive()`; trên `CAPTURED` ném `IllegalStateException`.
- `ReleaseReservationUseCase.java:80-96` — nhánh HTTP `tolerateClosed=false` biến lỗi đó thành `ConflictException` → 409.
- `ReservationController.java:77-81` — endpoint `POST /api/v1/reservations/{id}/release` dùng đúng nhánh strict đó.

## Key insight (đã kiểm chứng)

Hai kết luận, cả hai đọc trực tiếp từ code:

1. **`capture` đã là thao tác gộp.** Nó thu phần thực tế, trả phần chưa dùng và đóng hold trong một transaction. `release` sau đó không thêm ngữ nghĩa nào, chỉ tạo ra một lời gọi chắc chắn nhận 409. Đây là lỗi ghép hai endpoint có ngữ nghĩa xung khắc, không phải lỗi "thiếu endpoint one-for-all".

2. **`release(order)` là dead code ở cả hai nhánh.** Với `reservationId != null` và `estimated > 0`, `ChargeService.charge` không có đường nào trả `FAILED` — nó `capture` rồi trả `SUCCEEDED`. Với `reservationId == null`, `release(order)` là no-op vì `order.reservationId()` null. Vậy chính xác thì:
   - **Có reservation + hold còn hạn** → `SUCCEEDED` → `release()` → 409 → rollback → requeue → lặp tới `maxAttempts` → `FAILED` **dù tiền đã thu**. Đây là P0.
   - **Có reservation + hold hết hạn** → `capture` ném → `ChargeUseCase` đổi thành `ConflictException` → 409 → requeue. Không thu tiền, nên không phải P0, và `release` cũng không giúp gì (hold đã `EXPIRED`, `release` strict cũng 409).
   - **Không reservation** → `release()` no-op.

   Kết luận: xoá `release()` **hoàn toàn**, không chỉ ở nhánh thành công.

## Files to modify

- Modify `tenant/modules/execution/src/application/java/dev/ngb/tenant/execution/application/queue/command/ExecuteOrdersUseCase.java` — xoá lời gọi `release(...)` ở cả hai nhánh và xoá method `release(...)`.
- Modify `tenant/modules/execution/src/test/java/dev/ngb/tenant/execution/application/queue/command/ExecuteOrdersUseCaseTest.java` — ba chỗ assert `released` đang khoá bug.
- Modify `tenant/apps/app-boot/src/test/java/dev/ngb/tenant/support/FakeBilling.java` — thêm ghi nhận các lần `release` để test khẳng định nó **không** được gọi.
- Create `tenant/apps/app-boot/src/test/java/dev/ngb/tenant/execution/ExecuteOrdersSettlementIntegrationTest.java` — test qua `HttpIntegrationTest` + `FakeBilling`.

## Implementation steps

1. Trong `settle(...)`, xoá cả hai lời gọi `release(order)`. Nhánh sau khi sửa:

   ```java
   Charge charge = charge(order);
   if (charge != null && "FAILED".equals(charge.status())) {
       orders.markFailed(orderId, charge.failureReason());
       queue.finish(orderId);
       return false;
   }
   orders.markCompleted(orderId);
   queue.finish(orderId);
   return true;
   ```

2. Xoá method `private void release(ExecutionOrders.ExecutionOrder order)`. Nó không còn caller. Không giữ lại với comment "removed" — xoá hẳn.

3. Cập nhật javadoc class: thay "usage, charge, release and the terminal status commit together" bằng câu nói rõ `capture` đã trả lại phần chưa dùng, nên settle không release lần nữa; một reservation đã capture không thể và không cần release.

4. Sửa `ExecuteOrdersUseCaseTest`:
   - `runsRecordsUsageCapturesReleasesAndCompletes` → đổi tên `runsRecordsUsageCapturesAndCompletes`, assert `charges.released` là `false`.
   - `aFailedChargeMarksTheOrderFailedAndReleasesTheHold` → đổi tên `aFailedChargeMarksTheOrderFailedAndLeavesNoHoldToRelease`; assert `released == false`. Test này hiện dùng `FakeCharges.failCharge = true` với `reservationId = "res_1"`, tức nó mô tả một trạng thái mà Billing thật **không thể** tạo ra (đã chứng minh ở Key insight). Sửa fake để nhánh `FAILED` ứng với `reservationId = null` — đúng như Billing thật.
   - `aHybridOrderRatesItsUsageWithoutAReservation` giữ nguyên.

5. Thêm vào `FakeBilling` một `List<String> releasedReservations` và accessor, đúng khuôn `chargedReservations` đang có (`FakeBilling.java:54, 83-85, 149-152`). Trong `release(String)` thì `releasedReservations.add(reservationId)` trước khi trả về.

6. Viết `ExecuteOrdersSettlementIntegrationTest` ở `tenant/apps/app-boot` dùng `FakeBilling` (không gọi mạng, theo `FakeBillingConfig`). Dựng order `QUEUED` với `reservationId = "res_1"`, `estimatedMinorUnits > 0`; chạy `ExecuteOrders.run(10)`; assert:
   - `orders` status `COMPLETED`.
   - `fakeBilling.chargedReservations()` chứa `"res_1"` đúng một lần.
   - `fakeBilling.releasedReservations()` **rỗng**.
   - `queue` đã `finish`, không exception.

7. **Kiểm chứng bug có thật, trước và sau (bắt buộc).** Chạy test ở bước 6 trên code **chưa sửa** (stash bước 1-2), ghi lại nó fail thế nào. Sau đó chạy lại trên code đã sửa, ghi lại nó pass. Lưu cả hai kết quả vào phần Verification khi thực thi — đây là bằng chứng thực thi, không phải suy luận.

## Verification

```bash
# 1. Test đơn vị: hành vi mới
cd tenant && ./gradlew :modules:execution:test

# 2. Test tích hợp: order có reservation kết thúc COMPLETED, không release
cd tenant && ./gradlew :apps:app-boot:test --tests '*ExecuteOrdersSettlement*'

# 3. Không còn lời gọi hay method release trong use case
grep -n "release" tenant/modules/execution/src/application/java/dev/ngb/tenant/execution/application/queue/command/ExecuteOrdersUseCase.java
# → không có hit nào

# 4. Billing không đổi semantics: release trên reservation đã capture vẫn 409
cd billing && ./gradlew :apps:app-boot:test --tests '*MoneyIntegrationTest*'

# 5. Toàn service
cd tenant && ./gradlew build
```

Bước 7 ở trên (chạy test trước khi sửa) là yêu cầu bắt buộc của phase: nó chứng minh test mới thật sự bắt được bug, chứ không phải pass sẵn.

## Verification evidence (đã chạy)

- `cd tenant && ./gradlew :modules:execution:test` — **xanh**, 8/8 ca, gồm `runsRecordsUsageCapturesAndCompletes`
  và `aFailedChargeMarksTheOrderFailedAndLeavesNoHoldToRelease`.
- `cd tenant && ./gradlew :apps:app-boot:test --tests '*ExecuteOrdersSettlement*'` — **PASSED**.
- **Bug có thật, trước khi sửa:** stash riêng `ExecuteOrdersUseCase.java` (giữ test mới) rồi chạy lại ca tích hợp,
  kết quả `FAILED — Expecting empty but was: ["res_1"]` khi assert `billing.releasedReservations()` rỗng. Đó là
  `release` được gọi trên reservation vừa capture.
- Pop bản sửa lại: cùng ca **PASSED**.
- `grep -n "release" ExecuteOrdersUseCase.java` → chỉ còn comment/javadoc, không còn lời gọi hay method.
- `cd billing && ./gradlew :apps:app-boot:test --tests '*MoneyIntegrationTest*'` — **xanh** cả 14 ca, gồm
  `reservationsHoldCaptureReleaseAndReplay`.
- `cd tenant && ./gradlew build` — **BUILD SUCCESSFUL**.

## Success criteria

- [x] `ExecuteOrdersUseCase` không còn lời gọi `release(...)` và không còn method `release(...)`.
- [x] `ExecuteOrdersUseCaseTest` không còn assert `released == true` ở bất kỳ test nào.
- [x] `FakeCharges.failCharge` chỉ được dùng với `reservationId == null`, khớp hành vi Billing thật.
- [x] Test tích hợp: order có reservation → `COMPLETED`, đúng một `capture`, không `release`, không exception.
- [x] Test tích hợp **fail trên code cũ** và **pass trên code mới** (ghi lại cả hai lần chạy).
- [x] `MoneyIntegrationTest` ở Billing vẫn xanh — semantics `release` trên `CAPTURED` = 409 không đổi.
- [x] `cd tenant && ./gradlew build` xanh.

## Risks

- **Bỏ sót kịch bản cần nhả hold.** Bất biến đang dựa vào: có reservation ⇒ `charge` luôn capture ⇒ không còn hold để nhả. Nếu tương lai thêm nhánh mà `charge` trả `null` với `reservationId != null`, hold sẽ treo tới khi `ExpireReservationsUseCase` ở Billing dọn. Mitigation: thêm test khẳng định `estimatedMinorUnits == 0` ⇒ `reservationId == null`, và một comment tại `charge(...)` nêu bất biến đó.
- **Test cũ đang khoá bug.** Nếu ai chỉ "sửa test cho xanh" mà không đọc Key insight, họ có thể thêm lại `release`. Mitigation: test mới assert `releasedReservations()` rỗng, khoá hành vi chứ không chỉ khoá cờ boolean.
- **Không sửa được dữ liệu đã hỏng.** Order đã `FAILED` với tiền đã thu vẫn nằm đó. Xem Follow-up trong `plan.md`.

## Security considerations

Không đổi mô hình bảo mật hay quyền. Việc bỏ một lời gọi `POST /api/v1/reservations/{id}/release` (cần `RESERVATION_MANAGE`) làm giảm bề mặt call, không tăng. Không có dữ liệu nhạy cảm mới trong log hay test.

## Next steps

Phase 5 chạm cùng module `execution` (tách HTTP ra khỏi transaction) nên phải land sau phase này để tránh sửa hai lần trên cùng method.
