---
phase: 1
title: "Terraform foundation"
status: completed
priority: P2
effort: M
dependencies: []
---

# Phase 1: Terraform foundation

## Goal

Dựng nền `ops/terraform/`: provider + backend, VPC + security groups, ECS cluster (Fargate), ECR repos, Service Connect
namespace, và ALB bare (listener với default action 503 tạm). `.gitignore` chặn tfvars/state.

## Findings addressed

- Repo chưa có hạ tầng AWS nào: `ops/` chỉ có caddy, keycloak, postgres, rabbitmq, smoke, loadtest. Không có Terraform.
- `.gitignore` chặn `.env`, `*.key`/`*.pem` nhưng không có mục cho Terraform (`*.tfvars`, `*.tfstate*`, `.terraform/`).
- Compose chạy trên network local; ECS cần VPC với public subnet cho ALB và private subnet cho Fargate.
- 4 image cần ECR: `platform` (`platform/apps/app-boot/Dockerfile`), `billing` (`billing/apps/app-boot/Dockerfile`, dùng
  chung cho migrate/api/worker), `keycloak` (custom, xem phase 3). `tenant` không deploy.

## Files to create / modify

- Create `ops/terraform/main.tf` — provider + backend + data.
- Create `ops/terraform/variables.tf` — region, env, domain placeholder, Service Connect namespace.
- Create `ops/terraform/outputs.tf`.
- Create `ops/terraform/network/vpc.tf`, `network/subnets.tf`, `network/security-groups.tf`.
- Create `ops/terraform/ecs/cluster.tf`, `ecs/ecr.tf`.
- Create `ops/terraform/service-discovery/namespace.tf`.
- Create `ops/terraform/alb/alb.tf` — ALB + listener (default action 503 tạm).
- Modify `.gitignore` — thêm khối Terraform.

## Tasks

1. `main.tf`: provider `hashicorp/aws`, region từ variable. Backend `s3` + DynamoDB lock (khuyến nghị) hoặc local nếu
   chưa có bucket; ghi lựa chọn trong comment. Nếu chọn S3, tạo bucket + DynamoDB table bằng `null_resource`/thủ công
   một lần và ghi rõ.
2. `variables.tf`: `region`, `environment`, `public_domain` (placeholder), `service_connect_namespace` (mặc định
   `core-billing.local`). Khai báo local `internal_fqdn = "platform.${var.service_connect_namespace}"` để là nguồn sự thật
   duy nhất cho FQDN nội bộ.
3. `network/`: VPC (CIDR riêng), 2 public subnet + 2 private subnet (2 AZ), Internet Gateway, NAT gateway, route tables.
   Security groups: default deny ingress; SG riêng cho ALB (mở 80/443 từ internet), SG cho Fargate task (mở 8080 nội bộ
   và từ ALB), SG cho RDS/MQ (phase 2 sẽ tham chiếu).
4. `ecs/cluster.tf`: `aws_ecs_cluster` với capacity provider Fargate, không EC2. `ecs/ecr.tf`: 3 repo
   `platform`, `billing`, `keycloak` (force delete true cho dev), lifecycle policy giữ N image.
5. `service-discovery/namespace.tf`: `aws_service_discovery_private_dns_namespace` tên từ variable. Ghi comment: FQDN
   nội bộ mọi service có dạng `<service>.<namespace>`.
6. `alb/alb.tf`: `aws_lb` internet-facing (public subnet), `aws_lb_listener` HTTP (hoặc HTTPS nếu có cert, ghi rõ). Default
   action = fixed response 503 với comment "thay bằng forward tới billing target group ở phase 5". `aws_lb_target_group`
   chưa tạo ở đây.
7. `.gitignore` thêm:

   ```
   # Terraform local state and variable overrides
   *.tfvars
   *.tfstate
   *.tfstate.*
   .terraform/
   ops/terraform/.terraform.lock.hcl
   ```

   Không chặn `.terraform.lock.hcl` nếu muốn commit lock file (khuyến nghị commit, nên để lại và bỏ dòng cuối nếu dùng).

## Verification

- `terraform fmt -check -recursive ops/terraform/`
- `terraform -chdir=ops/terraform init` rồi `terraform -chdir=ops/terraform validate` (không cần AWS creds).
- `git check-ignore -v ops/terraform/terraform.tfvars` → khớp rule mới.
- `grep -rn "platform.core-billing.local\|platform.\${" ops/terraform/` → FQDN nội bộ chỉ xuất hiện qua local, không
  hardcode rải rác.

## Success criteria

- [x] Terraform init/validate sạch, `fmt -check` sạch.
- [x] VPC có public + private subnet (2 AZ), NAT cho private.
- [x] ECS cluster Fargate, 3 ECR repo.
- [x] Service Connect private namespace `core-billing.local`.
- [ ] ALB + listener default 503, chưa có target group. (Trạng thái cuối: listener forward Billing, target group đã có.)
- [x] `.gitignore` chặn tfvars/state.

## Risks

- **FQDN nội bộ hardcode rải rác.** Nếu namespace đổi tên, mọi tham chiếu vỡ. Mitigation: một local duy nhất
  `internal_fqdn` (và các local tương tự cho keycloak/billing), mọi task def tham chiếu local đó.
- **Backend state.** Local state không share được giữa máy/CI. Mitigation: chốt S3 + DynamoDB ngay từ đầu nếu có account;
  nếu chưa, ghi rõ đang dùng local và liệt kê bước migrate sau.
- **Listener default action 503 là tạm.** Nếu phase 6 không đổi sang Billing, mọi request không khớp rule trả 503.
  Mitigation: ghi rõ trong comment và có bước kiểm ở phase 6.

## Security considerations

Không có secret trong `.tf` hay `.tfvars` committed. Secrets sẽ vào AWS Secrets Manager (phase 2) và được task def tham
chiếu qua ARN. Security group least privilege: Fargate task không mở cổng ra internet ngoài những gì cần; RDS/MQ private
subnet.

## Next steps

Phase 2 dùng VPC/security groups từ đây để dựng RDS + Amazon MQ + Secrets Manager.
