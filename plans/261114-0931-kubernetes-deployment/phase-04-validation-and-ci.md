---
phase: 4
title: "Validation, CI and documentation"
status: pending
priority: P2
effort: S
dependencies: [1, 2, 3]
---

# Phase 4: Validation, CI and documentation

## Goal

Manifest được validate tự động trong CI, tài liệu nói rõ hai đường deploy song song và không còn mô tả compose là
đường duy nhất.

## Findings addressed

- `.github/workflows/suite.yml` chỉ chạy `docker compose up --wait`, `isolation.sh`, `suite-smoke.sh`. Không có bước nào
  kiểm tra manifest Kubernetes.
- `docs/runtime-profiles.md:30-44` mô tả "The suite's processes" chỉ bằng thuật ngữ compose.
- `README.md` hướng dẫn "Run the whole suite" chỉ bằng `docker compose`.

## Files to create / modify

- Create `.github/workflows/manifests.yml` — workflow riêng cho `ops/k8s/`, để `suite.yml` không bị đụng.
- Modify `docs/runtime-profiles.md` — thêm mục Kubernetes.
- Modify `README.md` — thêm dòng chỉ tới `ops/k8s/`.

## Tasks

1. `.github/workflows/manifests.yml`:

   ```yaml
   name: manifests

   on:
     pull_request:
       paths: ["ops/k8s/**", ".github/workflows/manifests.yml"]
     push:
       branches: [main, dev]
       paths: ["ops/k8s/**", ".github/workflows/manifests.yml"]

   jobs:
     validate:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - name: Validate Kubernetes manifests
           run: |
             curl -sSLo kubeconform.tar.gz \
               https://github.com/yannh/kubeconform/releases/latest/download/kubeconform-linux-amd64.tar.gz
             tar xzf kubeconform.tar.gz kubeconform
             ./kubeconform -strict -summary -ignore-missing-schemas ops/k8s/
   ```

   `-ignore-missing-schemas` cần nếu không pin version schema; nếu muốn chặt hơn, pin `-kubernetes-version` theo version
   cluster đích. Ghi lựa chọn trong comment.

2. `docs/runtime-profiles.md`: thêm mục `## Kubernetes` với các điểm:
   - Ba workload tương ứng ba profile: Job `migrate`, Deployment `api`, Deployment `worker`.
   - Thứ tự: Job xong rồi api/worker; không có `depends_on` nên dùng initContainer hoặc retry (ghi cách đã chọn).
   - Hạ tầng external: PostgreSQL, RabbitMQ, Keycloak cấu hình qua env; không có StatefulSet.
   - Billing gọi Platform qua FQDN nội bộ, và Platform phải khai báo FQDN đó trong `PLATFORM_INTERNAL_HOSTNAMES`.
   - `docker compose` vẫn là đường deploy hạng nhất cho local; hai đường dùng cùng một jar và cùng `application.yml`.

3. `README.md`: thêm một dòng trong mục "Run the whole suite" (hoặc ngay sau) chỉ tới `ops/k8s/README.md` hoặc
   `ops/k8s/`, nêu rõ đây là đường thay thế song song, không thay thế compose.

4. Thêm `ops/k8s/README.md` ngắn: cách copy `secret.example.yaml` thành `secret.yaml`, điền giá trị, `kubectl apply`
   theo thứ tự namespace → configmap/secret → Job migrate → Platform → Billing.

## Verification

- `actionlint .github/workflows/manifests.yml` nếu có; nếu không, kiểm tra cú pháp YAML bằng
  `ruby -ryaml -e 'YAML.load_file(ARGV[0])' .github/workflows/manifests.yml`.
- `kubeconform -strict -summary ops/k8s/`
- `grep -n "ops/k8s" README.md docs/runtime-profiles.md` → có hit ở cả hai.
- `git diff docker-compose.yml` rỗng.
- `git diff --stat` chỉ chứa `ops/k8s/`, `.gitignore`, `docs/`, `README.md`, `.github/workflows/`.

## Success criteria

- [ ] Có workflow validate manifest, chỉ trigger theo `ops/k8s/**`, không đụng `suite.yml`.
- [ ] `kubeconform -strict -summary ops/k8s/` exit 0.
- [ ] `docs/runtime-profiles.md` có mục Kubernetes mô tả đúng ba profile và thứ tự khởi động.
- [ ] `README.md` chỉ tới đường Kubernetes như lựa chọn song song.
- [ ] `ops/k8s/README.md` có hướng dẫn apply theo thứ tự.
- [ ] `docker-compose.yml` không đổi.

## Risks

- **`kubeconform` chỉ bắt lỗi schema.** Sai tên biến, sai port, thiếu key trong Secret đều lọt. Đây là khoảng trống đã
  biết và đã được chấp nhận khi chọn mức validate này. Ghi lại trong tài liệu để người sau không tưởng manifest đã
  được chứng minh chạy.
- **`curl` tới `releases/latest`** làm build phụ thuộc mạng và có thể đổi version bất ngờ. Mitigation: ghi rõ trong
  comment rằng nên pin version khi workflow ổn định.
- **Hai workflow cùng chạy khi một PR đụng cả `ops/k8s/` và code.** Đó là hành vi mong muốn; không cần `needs`.

## Security considerations

Workflow chỉ đọc repo và chạy binary công khai; không cần secret. Không in nội dung Secret ra log. `secret.example.yaml`
chỉ có placeholder nên không có gì để lộ.

## Next steps

Sau phase 4, khi có cluster thật: điền `secret.yaml`, đặt domain thật, chạy `kubectl apply`, rồi chạy lại
`ops/smoke/suite-smoke.sh` trỏ vào ingress để chứng minh end-to-end. Việc đó nằm ngoài plan này vì cần cluster — ghi
lại như follow-up.
