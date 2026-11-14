# contracts

Documents the suite's services share, never code. The only code they share is the technical `core/`.

| Path | Owns |
|---|---|
| `openapi/billing.yaml` | Billing's REST API. Generated from the code and checked by a test; regenerate with `./gradlew :app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`. |
| `openapi/billing-tenant-system.yaml` | The same API filtered to the operations a Tenant System may call with its suite token, generated from the same document by the same test. Never edit it by hand. |
| `openapi/platform.yaml` | Platform's REST API. Generated from the code and checked by a test; regenerate with `./gradlew :apps:app-boot:test --tests '*PlatformOpenApiContractTest' -Dopenapi.update=true`. |
| `events/billing/` | Billing's outbound events: one JSON Schema per type, plus how a webhook delivery is signed and verified. See [events/README.md](events/README.md). |
| `events/platform/` | Platform's replicated state-transfer events (`*.changed`). |
| `messaging/` | The AMQP envelope, topology, delivery guarantees and the dead-letter runbook. See [messaging/README.md](messaging/README.md). |
| `permissions/` | One permission catalog per app (`billing.yaml`, `platform.yaml`): `code`, `description`, `scopes`, `grantable_to_tenant_system`. Platform validates credential scopes against it and seeds roles from it. |
| `tokens/` | The claims of Platform-issued suite tokens for service clients and Tenant Systems. See [tokens/README.md](tokens/README.md). |

An app's OpenAPI document and event schemas are the executable owners of its API and event shapes; these files are
the documents every service reads to agree on them. See [docs/architecture.md](../docs/architecture.md).
