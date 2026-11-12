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

## Verification

Apps verify the token against `GET /.well-known/jwks.json` (ES256, `kid` is the key thumbprint), require the
expected `aud`, and require `exp`/`iat` in the accepted window. The private signing key lives only in Platform,
loaded from `PLATFORM_TOKEN_SIGNING_KEY` or a mounted file. Rotation publishes the new key in the JWKS and keeps
the previous one for one token lifetime.
