---
phase: 3
title: "Billing workloads and migrate job"
status: pending
priority: P2
effort: M
dependencies: [1, 2]
---

# Phase 3: Billing workloads and migrate job

## Goal

Billing chạy đủ ba hình thái trên Kubernetes — migrate một lần, api, và worker — với thứ tự khởi động đúng, và gọi
Platform qua FQDN nội bộ.

## Findings addressed

- `docker-compose.yml` dùng `depends_on: condition: service_completed_successfully` để bảo đảm `billing-migrate` xong
  trước khi `billing` và `billing-worker` start. Kubernetes không có tương đương.
- `application-migrate.yml` chạy với `spring.main.web-application-type: none`, `flyway.enabled: true`, job tắt.
- `application-api.yml` chạy Flyway off, virtual threads on, pool 20, `connection-timeout: 3000`.
- `application-worker.yml` chạy Flyway off, virtual threads **off**, scheduler pool 9, graceful 30s.
- `document`/`README` ghi deploy theo thứ tự `migrate`, `api`, `worker`.

## Files to create / modify

- Create `ops/k8s/billing-migrate-job.yaml`.
- Create `ops/k8s/billing-deployment.yaml`.
- Create `ops/k8s/billing-worker-deployment.yaml`.
- Create `ops/k8s/billing-service.yaml`.

## Tasks

1. `billing-migrate-job.yaml`:
   - `kind: Job`, `backoffLimit: 1` (migrate thất bại nên dừng, không retry mù), `ttlSecondsAfterFinished` hợp lý để
     dọn.
   - `envFrom` ConfigMap + Secret như Deployment.
   - `env: SPRING_PROFILES_ACTIVE: migrate`.
   - `restartPolicy: Never`.
   - Comment: `ttl` phải đủ dài để debug khi lỗi; nếu cần giữ log, dùng `ttlSecondsAfterFinished` lớn hoặc bỏ.

2. `billing-service.yaml`: `ClusterIP`, port 80 → 8080, selector khớp `billing` Deployment.

3. `billing-deployment.yaml`:
   - `replicas: 2`.
   - `env: SPRING_PROFILES_ACTIVE: api`.
   - `initContainer` chờ schema sẵn sàng thay cho `depends_on`. Hai lựa chọn, chọn một và ghi lý do trong comment:
     - **a)** `initContainer` chạy `psql`/`pg_isready` tới database rồi kiểm tra `flyway_schema_history` có bản ghi
       thành công.
     - **b)** Không dùng initContainer; để container chính fail và retry, vì api chạy Flyway off nên sẽ fail với lỗi
       rõ ràng cho tới khi schema có. Đơn giản hơn nhưng log nhiễu.
   - Probes như Platform (`/actuator/health/readiness`, `/actuator/health/liveness`).
   - `terminationGracePeriodSeconds: 30`.
   - `securityContext` như Platform.

4. `billing-worker-deployment.yaml`:
   - `replicas: 2`.
   - `env: SPRING_PROFILES_ACTIVE: worker`.
   - Cùng initContainer/chiến lược chờ như api.
   - Probes như api; worker không có controller nghiệp vụ nhưng vẫn có actuator.
   - `terminationGracePeriodSeconds: 30` khớp `await-termination-period: 30s`.

5. **Ba biến phải đúng, và đây là chỗ dễ sai nhất.** Trong ConfigMap (phase 1) hoặc ghi đè ở đây:
   - `BILLING_PLATFORM_BASE_URL=http://platform.core-billing.svc.cluster.local` — **đổi** sang DNS nội bộ.
   - `BILLING_PLATFORM_JWK_SET_URI=http://platform.core-billing.svc.cluster.local/.well-known/jwks.json` — **đổi**.
   - `PLATFORM_TOKEN_ISSUER` (Platform) và `BILLING_PLATFORM_ISSUER` (Billing) — **giữ nguyên** host công khai ổn định.
     Đổi hai biến này làm mọi suite token bị từ chối; xem journal `2026-11-14-fix-issuer-fallback-traps`.

6. `PLATFORM_INTERNAL_HOSTNAMES` phải chứa `platform.core-billing.svc.cluster.local` (đặt ở ConfigMap cho Platform),
   nếu không các call ở bước 5 nhận 404.

7. Worker không bật virtual threads: không đặt `spring.threads.virtual.enabled` trong env của worker. Đây là hành vi đã
   có trong `application-worker.yml`; manifest không được vô tình ghi đè.

## Verification

- `kubeconform -strict ops/k8s/billing-*.yaml`
- `grep -n "SPRING_PROFILES_ACTIVE" ops/k8s/*.yaml` → đúng ba giá trị `migrate`, `api`, `worker`, mỗi giá trị một file.
- `grep -n "BILLING_PLATFORM_BASE_URL" ops/k8s/*.yaml` → giá trị chứa `svc.cluster.local`, không chứa IP.
- `grep -n "TOKEN_ISSUER\|PLATFORM_ISSUER" ops/k8s/*.yaml` → không có `svc.cluster.local` trong giá trị issuer.
- `grep -n "spring.threads.virtual" ops/k8s/billing-worker-deployment.yaml` → không có.

## Success criteria

- [ ] Job migrate tồn tại với `SPRING_PROFILES_ACTIVE=migrate` và `restartPolicy: Never`.
- [ ] api và worker là Deployment riêng, mỗi cái 2 replica, profile đúng.
- [ ] Có cơ chế chờ schema trước khi api/worker phục vụ, và lý do được ghi trong comment.
- [ ] `BILLING_PLATFORM_BASE_URL` và `BILLING_PLATFORM_JWK_SET_URI` trỏ DNS nội bộ.
- [ ] Hai biến issuer giữ nguyên host công khai.
- [ ] Worker không bật virtual threads.
- [ ] `kubeconform -strict` sạch cho bốn file.

## Risks

- **Thứ tự migrate.** Đây là chỗ Kubernetes khó hơn compose. Mitigation: chọn một trong hai cách ở Task 3, ghi lý do;
  cách a) chính xác hơn, cách b) đơn giản hơn. Không dùng `depends_on` (không tồn tại trong Kubernetes).
- **Hai worker cùng chạy job.** Các job đã được thiết kế multi-worker safe (`for update skip locked`, lease trong
  `job_leases`, `job_runs`), nên `replicas: 2` là an toàn về mặt logic. Vẫn nên xác nhận khi có cluster thật.
- **Hai api cùng rebuild replica.** `ReplicaBootstrap` chạy ở profile worker trong Billing (`ReplicaConfiguration`
  `@Profile("worker")`), nên api không rebuild. Worker 2 replica cùng rebuild: xem lại khi có cluster.
- **Postgres external chưa có.** Manifest có thể validate nhưng không chạy được cho tới khi có database thật và Secret
  đã điền.

## Security considerations

Secret mount qua `envFrom`, không ghi giá trị vào Deployment. `runAsNonRoot`, `allowPrivilegeEscalation: false`.

`BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS` phải `"false"` trên cluster (compose đặt `"true"` cho local) — nếu không,
webhook có thể gọi tới địa chỉ nội bộ tuỳ ý, đúng loại SSRF mà cờ này tồn tại để chặn. `BILLING_PORTAL_SECURE_COOKIE`
phải `"true"`.

## Next steps

Phase 4 thêm bước validate vào CI và ghi tài liệu.
