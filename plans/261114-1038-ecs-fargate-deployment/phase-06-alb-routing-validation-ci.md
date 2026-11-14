---
phase: 6
title: "ALB routing, validation and CI"
status: completed
priority: P2
effort: M
dependencies: [3, 4, 5]
---

# Phase 6: ALB routing, validation and CI

## Goal

Hoàn thiện ALB routing (default action → Billing), thêm CI validate/plan Terraform và pipeline deploy (migrate → deploy),
ghi tài liệu hai đường deploy song song, và chạy smoke test chứng minh end-to-end.

## Findings addressed

- `.github/workflows/suite.yml` chỉ chạy `docker compose up --wait` + smoke; không có bước nào cho Terraform.
- ALB listener (phase 1) đang có default action 503 tạm; giờ Billing target group đã tồn tại (phase 5).
- `docs/runtime-profiles.md` và `README.md` chỉ mô tả deploy bằng `docker compose`.

## Files to create / modify

- Modify `ops/terraform/alb/alb.tf` — default action → Billing target group; xác nhận rules từ phase 3/4.
- Create `.github/workflows/terraform.yml` — validate + plan Terraform.
- Create `.github/workflows/deploy.yml` — migrate → deploy (optional, nếu muốn CI deploy).
- Create `ops/terraform/README.md` — hướng dẫn init/plan/apply, điền tfvars, thứ tự.
- Modify `docs/runtime-profiles.md` — thêm mục ECS.
- Modify `README.md` — chỉ tới `ops/terraform/` như đường deploy song song.

## Tasks

1. `alb.tf`: đổi default action của listener từ fixed 503 sang forward tới Billing target group. Giữ rules: path
   `/platform/*` + host `platform.<domain>` → Platform; host `auth.<domain>` → Keycloak. Ghi rõ thứ tự ưu tiên ALB rules
   (đánh số) khớp ngữ nghĩa Caddy: path/host rule trước, default là Billing.
2. `.github/workflows/terraform.yml`:
   - Trigger theo `ops/terraform/**`, `.github/workflows/terraform.yml`.
   - Steps: checkout → setup terraform (hashicorp/setup-terraform) → `terraform fmt -check -recursive` → `terraform init`
     → `terraform validate`. `plan` chỉ chạy khi có AWS credentials (OIDC), nếu chưa có thì bỏ và ghi rõ.
3. `.github/workflows/deploy.yml` (nếu bật CI deploy): OIDC tới AWS → `terraform apply` → `aws ecs run-task` (migrate) →
   poll `STOPPED` + exit 0 → `aws ecs update-service --force-new-deployment` cho billing api + worker. Nếu chưa cần CI
   deploy, ghi lệnh tương đương trong `ops/terraform/README.md` để chạy tay.
4. `docs/runtime-profiles.md`: thêm mục `## ECS Fargate` — ba workload (migrate one-shot, api, worker), Keycloak self-host,
   Service Connect cho service-to-service, RDS/MQ managed, `PLATFORM_INTERNAL_HOSTNAMES`, issuer public vs JWKS internal,
   `docker compose` vẫn là đường local hạng nhất.
5. `README.md`: thêm một dòng chỉ tới `ops/terraform/README.md`, nêu đây là đường deploy ECS song song, không thay thế
   compose.
6. Chạy smoke trên stack thật (cần sandbox): `ops/smoke/isolation.sh` + `suite-smoke.sh` trỏ vào ALB public URL.

## Verification

- `terraform fmt -check -recursive ops/terraform/`
- `terraform -chdir=ops/terraform validate`
- `grep -n "ops/terraform" README.md docs/runtime-profiles.md` → có hit ở cả hai.
- `grep -n "503" ops/terraform/alb/alb.tf` → không còn default 503 (đã forward Billing).
- `git diff docker-compose.yml` rỗng.
- `git diff --stat` chỉ chứa `ops/terraform/`, `.gitignore`, `.github/workflows/`, `docs/`, `README.md`.

## Success criteria

- [x] ALB default action → Billing; rules → Platform/Keycloak đúng thứ tự.
- [x] CI validate Terraform (`fmt -check` + `init` + `validate`).
- [x] Pipeline deploy (migrate → deploy) hoặc hướng dẫn tay tương đương trong README.
- [x] `docs/runtime-profiles.md` + `README.md` mô tả hai đường deploy song song.
- [x] `docker-compose.yml` không đổi.
- [ ] Smoke test pass trên stack thật (khi có sandbox).

## Risks

- **`terraform validate` chỉ bắt lỗi cú pháp.** Sai tên biến, sai port, thiếu secret đều lọt. Khoảng trống đã biết và đã
  chấp nhận (cùng bản chất như `kubeconform` ở k8s plan). Chứng minh end-to-end cần AWS sandbox thật — ghi rõ.
- **Hai đường deploy trôi khỏi nhau.** Mitigation: gate `grep` đối chiếu biến giữa `docker-compose.yml` và
  `ops/terraform/` (locals/variables). Liệt kê biến ở hai bên và diff.
- **ALB rule ưu tiên sai.** Nếu default action không phải Billing hoặc rule path/host xếp sai thứ tự, request đi nhầm
  service. Mitigation: đánh số ưu tiên rõ ràng, khớp ngữ nghĩa Caddyfile.

## Security considerations

CI dùng AWS OIDC (không long-lived key). Không in secret ra log. `secret`/`.tfvars` không commit; chỉ reference ARN trong
Terraform. `ops/terraform/README.md` không chứa giá trị thật.

## Next steps

Sau phase 6, khi có sandbox: điền `tfvars`, chạy `terraform apply`, chạy migrate, rồi smoke để chứng minh end-to-end. Việc
này cần account AWS thật nên nằm ngoài plan này — ghi lại như follow-up.
