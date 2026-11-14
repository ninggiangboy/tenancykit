---
title: "Kubernetes deployment for Platform and Billing"
description: "Add Kubernetes manifests for platform, billing api and billing-worker, keeping docker compose as an equal deployment path."
status: cancelled
priority: P2
effort: L
branch: dev
tags: [infra, backend, api, docs]
blockedBy: [261114-0930-service-discovery-internal-hostnames]
blocks: []
created: 2026-11-14
---

# Kubernetes deployment cho Platform và Billing

> **Superseded.** Plan này đã bị thay thế bởi `261114-1038-ecs-fargate-deployment` (chuyển hướng từ k8s sang ECS Fargate
> + Terraform). Giữ lại để tham chiếu lịch sử.

## Outcome

`ops/k8s/` chứa manifest cho `platform`, `billing` (api) và `billing-worker`, cộng Job migrate một lần, ConfigMap và
Secret template. `docker compose` vẫn là đường deploy song song, không đổi hành vi. Manifest validate được bằng
`kubeconform -strict` mà không cần cluster.

## Why (evidence)

Kết luận từ `plans/reports/advise-261114-0931-kubernetes-deployment.md`. Repo chưa có manifest Kubernetes nào:
`ops/` chỉ có caddy, keycloak, postgres init, rabbitmq users, smoke, loadtest, và `grep -ril 'kubernetes\|kubectl\|helm'`
trả về 0 kết quả.

`docker-compose.yml` đang gánh bốn vai trò mà Kubernetes phải tái tạo bằng cách khác:

- Tạo database và role: `ops/postgres/init/00-databases.sh`.
- Tạo broker user: `ops/rabbitmq/declare-users.sh`.
- Thứ tự `billing-migrate` → `billing` → `billing-worker` qua `depends_on: service_completed_successfully`.
- Route theo Host qua `ops/caddy/Caddyfile`.

Hạ tầng được chốt là external, nên hai vai trò đầu ra khỏi phạm vi manifest; vai trò thứ ba và thứ tư vẫn phải giải.

## Dependencies

`blockedBy: [261114-0930-service-discovery-internal-hostnames]` — manifest cần `PLATFORM_INTERNAL_HOSTNAMES` để Billing
gọi Platform bằng FQDN nội bộ mà không nhận 404. Nếu làm song song, manifest sẽ đúng về cú pháp nhưng stack không chạy.

## Non-goals

- StatefulSet cho PostgreSQL, RabbitMQ, Keycloak — external qua env.
- Deploy `tenant` và `web`.
- Dựng cluster trong CI.
- Helm hoặc Kustomize.
- `spring-cloud-kubernetes` discovery.
- Đổi schema `tenant_domains`.
- Bỏ service `billing-migrate` khỏi `docker-compose.yml`.
- Bypass Host resolution cho path nội bộ.

## Phases

| # | Phase | Depends on | Status |
|---|-------|-----------|--------|
| 1 | [Manifests foundation](./phase-01-manifests-foundation.md) | none | Pending |
| 2 | [Platform workload](./phase-02-platform-workload.md) | 1 | Pending |
| 3 | [Billing workloads and migrate job](./phase-03-billing-workloads-and-migrate.md) | 1, 2 | Pending |
| 4 | [Validation, CI and documentation](./phase-04-validation-and-ci.md) | 1, 2, 3 | Pending |

Phase 1 tạo namespace, ConfigMap và Secret template. Phase 2 và 3 có thể land song song về mặt file nhưng phase 3 cần
biết cấu hình Platform từ phase 2 để đặt đúng base URL nội bộ. Phase 4 là cổng kiểm chứng.

## Cross-cutting verification

- `kubeconform -strict -summary ops/k8s/`
- `cd platform && ./gradlew build`
- `cd billing && ./gradlew build`
- `docker compose up -d --build --wait && ops/smoke/isolation.sh && ops/smoke/suite-smoke.sh`
- `git diff docker-compose.yml` rỗng
- `git diff --stat` chỉ chứa `ops/k8s/`, `.gitignore`, `docs/`, `.github/workflows/`

## Global risks

- **Hai đường deploy trôi khỏi nhau.** Mỗi biến môi trường mới phải xuất hiện ở cả `docker-compose.yml` và
  `ops/k8s/configmap.yaml`. Mitigation: phase 4 thêm kiểm tra `grep` đối chiếu danh sách biến.
- **Thứ tự migrate không có tương đương `depends_on`.** Mitigation: Job + `initContainer` chờ; phase 3 nêu rõ cách và
  nêu phương án dự phòng (api/worker tự retry).
- **Issuer bị đổi nhầm sang DNS nội bộ.** Đây là bẫy đã có tiền lệ (journal `2026-11-14-fix-issuer-fallback-traps`).
  Mitigation: phase 3 liệt kê rõ biến nào đổi, biến nào giữ nguyên; phase 4 có grep gate.
- **Secret thật lọt vào git.** Mitigation: chỉ commit `secret.example.yaml`; `.gitignore` chặn `ops/k8s/secret.yaml`.
- **Manifest chưa được chạy thật.** `kubeconform` chỉ bắt lỗi schema. Khoảng trống này đã biết và đã được chấp nhận;
  ghi vào phần Risks của phase 4.

## Unresolved questions

- Ingress controller của cluster đích chưa xác định. Thiết kế giả định `ingressClassName: nginx`; nếu cluster dùng
  controller khác, chỉ đổi một dòng trong `ingress.yaml`. Không chặn việc tạo manifest.
- Domain công khai thật (dùng cho Ingress và `PLATFORM_HOSTNAME`) chưa biết. Manifest dùng placeholder rõ ràng và
  ConfigMap giữ biến, không hardcode.
