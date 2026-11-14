---
phase: 2
title: "Platform workload"
status: pending
priority: P2
effort: M
dependencies: [1]
---

# Phase 2: Platform workload

## Goal

Platform chạy trên Kubernetes với hai replica, Service nội bộ cho Billing gọi, và Ingress cho hostname admin.

## Findings addressed

- Platform hiện chỉ chạy được qua `docker-compose.yml` (service `platform`, port `8082:8080`, healthcheck qua
  `/actuator/health/readiness`).
- Compose dùng network alias `platform.localhost` để Host khớp domain đã đăng ký — trên Kubernetes cơ chế đó không có.
- `application.yml:84-92` đã bật readiness/liveness probes; compose khai thác chúng qua `bash /dev/tcp`.
- Không có Service nào cho phép Billing gọi Platform bằng tên ổn định.

## Files to create / modify

- Create `ops/k8s/platform-deployment.yaml`.
- Create `ops/k8s/platform-service.yaml`.
- Create `ops/k8s/platform-ingress.yaml`.

## Tasks

1. `platform-deployment.yaml`:
   - `replicas: 2`.
   - `template.spec.containers[0]`: image placeholder `<registry>/core-billing/platform:<tag>`, port `8080`.
   - `envFrom` ConfigMap `core-billing-config` và Secret `core-billing-secrets`.
   - `resources.requests`/`limits` theo tinh thần compose (`mem_limit: 512m`): request `256Mi`/`250m`, limit `512Mi`.
   - Readiness: `httpGet /actuator/health/readiness` port 8080; liveness: `/actuator/health/liveness`.
     `initialDelaySeconds` đủ lớn cho Flyway chạy lúc start (compose đặt `retries: 60` với `interval: 5s`, tức 300s
     trần) — dùng `initialDelaySeconds: 30`, `periodSeconds: 10`, `failureThreshold: 30` cho readiness và
     `failureThreshold: 6` cho liveness.
   - `terminationGracePeriodSeconds: 30` khớp `timeout-per-shutdown-phase: 30s`.
   - `securityContext`: `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` nếu ảnh
     không cần ghi đĩa (Dockerfile chỉ copy jar và chạy `java -jar`).
   - `strategy.rollingUpdate`: `maxUnavailable: 0`, `maxSurge: 1` — Platform migrate lúc start nên không muốn hai pod
     cùng chạy Flyway tranh nhau; xem Risks.

2. `platform-service.yaml`: `ClusterIP`, port 80 → targetPort 8080, selector khớp label của Deployment. Tên Service
   phải là `platform` để FQDN `platform.<namespace>.svc.cluster.local` đúng như ConfigMap dự kiến.

3. `platform-ingress.yaml`: host placeholder `<platform-host>` lấy từ `PLATFORM_HOSTNAME`, `ingressClassName: nginx`,
   path `/` → Service `platform` port 80. Chỉ phục vụ admin host công khai; lưu lượng nội bộ đi thẳng qua Service.

4. Ghi rõ trong comment: `PLATFORM_INTERNAL_HOSTNAMES` phải chứa `platform.<namespace>.svc.cluster.local`, nếu không
   Billing gọi bằng FQDN nội bộ sẽ nhận 404 (chi tiết ở plan `261114-0930-service-discovery-internal-hostnames`).

## Verification

- `kubeconform -strict ops/k8s/platform-deployment.yaml ops/k8s/platform-service.yaml ops/k8s/platform-ingress.yaml`
- `kubectl --dry-run=client -f ops/k8s/platform-deployment.yaml` (nếu có kubectl, không cần cluster)
- Đối chiếu thủ công: name của Service khớp hostname dự kiến trong ConfigMap
  (`PLATFORM_INTERNAL_HOSTNAMES=platform.core-billing.svc.cluster.local`).

## Success criteria

- [ ] Deployment có 2 replica, probes trỏ đúng path, graceful 30s.
- [ ] Service tên `platform`, port 80 → 8080.
- [ ] Ingress trỏ host admin, không hardcode domain thật.
- [ ] Comment nêu ràng buộc giữa `PLATFORM_INTERNAL_HOSTNAMES` và tên Service.
- [ ] `kubeconform -strict` sạch cho ba file.

## Risks

- **Platform migrate lúc start với `replicas: 2`.** Platform chạy Flyway khi khởi động (`docker-compose.yml` ghi
  "Platform and the tenant migrate on start"). Hai pod cùng migrate có thể tranh nhau; Flyway có lock nên một pod chờ,
  nhưng readiness sẽ trễ. Mitigation: `maxSurge: 1` và `maxUnavailable: 0`; nếu quan sát thấy tranh chấp, chuyển sang
  Job migrate cho Platform như Billing. Đây là quyết định cần đo, ghi vào ghi chú phase.
- **Ảnh chưa có registry path thật.** Mitigation: dùng placeholder rõ ràng, ghi vào phase 4.
- **`readOnlyRootFilesystem` có thể vỡ nếu JVM cần thư mục tạm.** Dockerfile đặt `WORKDIR /app` và chạy `java -jar`;
  nếu vỡ, bỏ cờ này và giữ các cờ khác.

## Security considerations

`runAsNonRoot: true` khớp Dockerfile (tạo user `app`). Không mount secret thành file nếu không cần; `envFrom` là đủ cho
cấu hình hiện tại. Ingress chỉ route tới Service nội bộ, không mở endpoint nào mới của ứng dụng.

## Next steps

Phase 3 dùng Service `platform` làm đích cho `BILLING_PLATFORM_BASE_URL`.
