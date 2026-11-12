# Suite tokens

Platform is the only issuer. A suite token authenticates a **service client** (one app calling another) or a
**Tenant System** (a third party's application calling an app with its API credential). Users never get one:
Keycloak keeps authenticating them.

## Obtaining a token

`POST /oauth/token` on Platform, `Content-Type: application/x-www-form-urlencoded`:

```
grant_type=client_credentials&audience=billing
```

Client authentication is HTTP Basic: the client id and secret. Service clients are configured in Platform; a
Tenant System uses the credential id and secret issued by `platform:credential:manage`. A wrong secret answers
`401` with no detail. The answer is `{ "access_token": "...", "token_type": "Bearer", "expires_in": 600 }`.

## Claims

| Claim | Meaning |
|---|---|
| `iss` | Platform's issuer URL. |
| `sub` | `tenant-system/<id>` or `service/<client>`. |
| `aud` | Exactly one app code (`billing`, `notification`, `platform`). |
| `exp`, `iat` | Ten minutes of life, issued now. A consumer tolerates at most 30 seconds of clock skew. |
| `jti` | Unique per token, for logging. |
| `scope` | Space-separated permission codes, only those valid for the audience app. |
| `tenant_id`, `tenant_code` | Tenant Systems only: the Tenant the credential belongs to. |
| `tenant_system_id` | Tenant Systems only. |

A token minted for one audience is rejected by another app: the audience is checked, never inferred. A Tenant
System token never carries `platform:*` codes, and a service-client token carries only the scopes its
configuration allows.

### Example: a Tenant System token for Billing

The Tenant System's credential is presented as its `client_id` (the key id, `cbk_<key-id>_<secret>`) and its
secret. Requesting `audience=billing` yields, decoded:

```json
{
  "iss": "http://localhost:8082",
  "sub": "tenant-system/0198c1a0-0000-7000-8000-0000000000aa",
  "aud": "billing",
  "iat": 1762500000,
  "exp": 1762500600,
  "jti": "0198c1a0-7b1e-7c3e-9b52-0d6f6f1c2a10",
  "scope": "billing:invoice:read billing:wallet:read",
  "tenant_id": "0198c1a0-0000-7000-8000-000000000042",
  "tenant_code": "acme",
  "tenant_system_id": "0198c1a0-0000-7000-8000-0000000000aa"
}
```

The credential carried `billing:*` and `notification:*` codes; only the requested audience's codes reach `scope`.
The `scope` is ordered, so two answers with the same grant are byte-identical.

## Rate limiting and revocation

- The token endpoint is rate-limited per credential (`burst` and `per-second`, in PostgreSQL so every instance
  shares the buckets). The request past the burst answers `429` with `Retry-After: 1`. An unknown or invented
  key id spends one of a fixed set of shared buckets, so it can neither exhaust a real credential's budget nor
  create a row per attempt.
- Revoking a credential makes the next token request `401` immediately; tokens already issued stay valid until
  they expire, at most ten minutes. Disabling the **Tenant System** is the immediate kill switch: every app checks
  the replicated `tenant_systems.status` on each request (phase 7), so a disabled system is refused at once.

## Verification

Apps verify the token against `GET /.well-known/jwks.json` (ES256, `kid` is the key thumbprint), require the
expected `aud`, and require `exp`/`iat` in the accepted window. The private signing key lives only in Platform,
loaded from `PLATFORM_TOKEN_SIGNING_KEY` or a mounted file. Rotation publishes the new key in the JWKS and keeps
the previous one for one token lifetime.
