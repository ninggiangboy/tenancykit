---
title: Fix issuer fallback traps trong docker-compose
date: 2026-11-14
summary: Thống nhất PLATFORM_TOKEN_ISSUER và cấp fallback OIDC cho Platform khớp Billing
---

# Fix issuer fallback traps trong docker-compose

## What happened

Chẩn đoán + kiểm chứng 2 bẫy issuer fallback trong `docker-compose.yml`:
- `PLATFORM_TOKEN_ISSUER` fallback lệch: Platform stamp `iss = http://platform:8080`, Billing mong `http://platform.localhost:8080` → thiếu `.env` thì Billing từ chối suite token của Platform.
- `PLATFORM_OIDC_ISSUER_URI` / `PLATFORM_OIDC_JWK_SET_URI` mặc định rỗng, trong khi Billing có fallback → thiếu `.env` thì Platform 401 mọi Keycloak user token ("only suite tokens are accepted").

## Change

Sửa 3 dòng trong `docker-compose.yml`:
- `PLATFORM_TOKEN_ISSUER` → `${PLATFORM_TOKEN_ISSUER:-http://platform.localhost:8080}`
- `PLATFORM_OIDC_ISSUER_URI` → `${PLATFORM_OIDC_ISSUER_URI:-http://localhost:8081/realms/billing}`
- `PLATFORM_OIDC_JWK_SET_URI` → `${PLATFORM_OIDC_JWK_SET_URI:-http://keycloak:8080/realms/billing/protocol/openid-connect/certs}`

## Verification

- Shell resolve lại fallback: issuer/JWKS của Platform và Billing giờ khớp.
- `ruby -ryaml` parse `docker-compose.yml` → `YAML OK`.
- Không còn tham chiếu `http://platform:8080`.
- Unit test `SuiteJwtDecodersTest` (6/6) và `SuiteTokenIssuerTest` (3/3) vẫn xanh; fix là cấu hình, không đụng code.

## Decision

Giữ `.env.example` làm nguồn giá trị chuẩn; chỉ vá fallback để compose chạy đúng cả khi `.env` thiếu 3 biến này.

## Next steps

- Chạy smoke test end-to-end (`docker compose up -d --build --wait` + `ops/smoke/suite-smoke.sh`) hoặc push `dev` để CI chạy `suite.yml` — hiện 46 commit (gồm workflow) chưa push, nên đường token Keycloak thật chưa được chạy thật lần nào.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
