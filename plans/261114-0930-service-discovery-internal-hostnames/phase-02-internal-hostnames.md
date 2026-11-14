---
phase: 2
title: "Internal hostnames in configuration"
status: pending
priority: P1
effort: S
dependencies: [1]
---

# Phase 2: Internal hostnames in configuration

## Goal

Platform đăng ký được nhiều hostname cùng lúc: hostname admin công khai từ `platform-hostname`, cộng mọi FQDN nội bộ
từ một list cấu hình mới. Mặc định của list là **rỗng**, nên `docker compose` giữ nguyên hành vi.

## Findings addressed

- `PlatformProperties.java:25` khai báo `record Tenancy(String baseDomain, String platformHostname)` — không có chỗ cho
  hostname thứ hai.
- `PlatformBootstrap.java:32-35` chỉ gọi `ensurePlatformDomain.execute(hostname)` một lần.
- `platform/apps/app-boot/src/main/resources/application.yml:29-32` chỉ có `platform-hostname`.
- `docker-compose.yml:151` truyền `PLATFORM_HOSTNAME`; không có biến nào cho hostname nội bộ.

## Files to create / modify

- Modify `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/config/PlatformProperties.java` — `Tenancy` thêm
  `List<String> internalHostnames`, canonical constructor chuẩn hoá `null → List.of()`.
- Modify `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/startup/PlatformBootstrap.java` — đăng ký
  `platformHostname` rồi từng phần tử của `internalHostnames`.
- Modify `platform/apps/app-boot/src/main/resources/application.yml` — thêm
  `internal-hostnames: ${PLATFORM_INTERNAL_HOSTNAMES:}` dưới `platform.tenancy`.
- Create `platform/apps/app-boot/src/test/java/dev/ngb/platform/web/InternalHostnameIntegrationTest.java` — chứng minh
  FQDN nội bộ resolve ra `RequestScope.platform()`.

## Tasks

1. Sửa `PlatformProperties`:

   ```java
   public record Tenancy(String baseDomain, String platformHostname, List<String> internalHostnames) {

       public Tenancy {
           internalHostnames = internalHostnames == null ? List.of() : List.copyOf(internalHostnames);
       }
   }
   ```

   Giữ đúng convention của `serviceClients` trong cùng record (`PlatformProperties.java:9-11`).

2. Sửa `PlatformBootstrap.run` để đăng ký hostname chính rồi fan-out. Giữ nguyên `tenantContext.runAs(RequestScope.platform(), ...)`
   và phần bootstrap admin phía sau. Cần chịu được `properties.tenancy() == null` và
   `platformHostname == null` như code hiện tại (fallback `platform.localhost`).

3. `application.yml`:

   ```yaml
   platform:
     tenancy:
       base-domain: ${PLATFORM_BASE_DOMAIN:localhost}
       platform-hostname: ${PLATFORM_HOSTNAME:platform.localhost}
       # Extra hostnames Platform answers on, besides platform-hostname: internal DNS names a cluster uses to
       # reach it service-to-service. Empty by default, so a compose run registers only platform-hostname.
       internal-hostnames: ${PLATFORM_INTERNAL_HOSTNAMES:}
   ```

4. Test tích hợp: khởi động context với `PLATFORM_INTERNAL_HOSTNAMES` gồm một FQDN dạng
   `platform.core-billing.svc.cluster.local`, rồi:
   - `GET /actuator/health` với Host đó → 200 (đường bypass, không chứng minh được gì thêm).
   - `POST /oauth/token` hoặc `GET /internal/replica/tenants` với Host đó → **không** phải 404 do hostname lạ; nghĩa là
     filter đã resolve. Chọn endpoint không cần credential hợp lệ để test chỉ kiểm tra bước resolve.
   - Với `platform.localhost` → vẫn resolve như trước.
5. Test cấu hình: khi env không set, `internalHostnames` là list rỗng. Test này bảo vệ yêu cầu "compose không đổi".

## Verification

- `cd platform && ./gradlew :apps:app-boot:test --tests '*InternalHostname*'`
- `cd platform && ./gradlew build`
- `cd core && ./gradlew build` (không đổi gì, chạy để chắc chắn)
- `docker compose up -d --build --wait` rồi `ops/smoke/suite-smoke.sh` → vẫn xanh, chứng minh mặc định rỗng không phá
  compose.

## Success criteria

- [ ] `PlatformProperties.Tenancy` có `internalHostnames`, mặc định rỗng khi env không set.
- [ ] Hai hostname đăng ký → cả hai resolve `RequestScope.platform()`.
- [ ] List rỗng → chỉ `platformHostname` được đăng ký; compose không đổi hành vi.
- [ ] FQDN dạng `X.Y.Z.svc.cluster.local` hợp lệ với `Hostname` (mỗi label đúng regex `^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`).
- [ ] `./gradlew build` ở `platform/` xanh.

## Risks

- **Relaxed binding của `List<String>` trong record `@ConfigurationProperties`.** Chưa xác minh `PLATFORM_INTERNAL_HOSTNAMES=a,b`
  bind thành hai phần tử. Nếu không bind được, phương án dự phòng là nhận một `String` phân tách bằng dấu phẩy rồi tự
  split trong `Tenancy`. Test ở Task 5 sẽ phát hiện ngay.
- **Startup chậm hơn.** Mỗi hostname là một transaction ngắn; với một hoặc hai giá trị là không đáng kể.
- **Hostname sai chính tả làm startup fail.** `Hostname` ném `IllegalArgumentException` cho label không hợp lệ. Đây là
  hành vi mong muốn: sai cấu hình phải lộ ra lúc start, không phải lúc request.

## Security considerations

Chỉ hostname khai báo tường minh trong env mới được đăng ký. Không có cơ chế "chấp nhận mọi host nội bộ", nên bề mặt
tấn công vẫn là danh sách hữu hạn do người vận hành đặt.

`SuiteActorResolver` vẫn kiểm tra token `tenant_id` phải bằng tenant của host. Với domain `PLATFORM`, host resolve ra
platform scope nên suite token (audience = app) vẫn đi qua đúng đường cũ.

## Next steps

Phase 3 ghi biến mới vào `.env.example` và tài liệu. Plan `261114-0931-kubernetes-deployment` dùng biến này trong
ConfigMap.
