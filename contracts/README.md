# contracts

Documents the suite's services share, never code. The only code they share is the technical `core/`.

| Path | Owns |
|---|---|
| `openapi/billing.yaml` | Billing's REST API. Generated from the code and checked by a test; regenerate with `./gradlew :app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`. |
| `openapi/billing-tenant-system.yaml` | The same API filtered to the operations a Tenant System may call with its suite token, generated from the same document by the same test. Never edit it by hand. |
| `openapi/platform.yaml` | Platform's REST API. Generated from the code and checked by a test; regenerate with `./gradlew :apps:app-boot:test --tests '*PlatformOpenApiContractTest' -Dopenapi.update=true`. |
| `openapi/notification.yaml` | Notification's REST API. Generated from the code and checked by a test. |
| `openapi/email.yaml` | Email's REST API. A resource skeleton in phase 1, filled by each phase and checked against the controllers. |
| `events/billing/` | Billing's outbound events: one JSON Schema per type, plus how a webhook delivery is signed and verified. See [events/README.md](events/README.md). |
| `events/platform/` | Platform's replicated state-transfer events (`*.changed`). |
| `events/mailer/` | Mailer's outbound events: one JSON Schema per type, today `email-delivery.changed`, the terminal delivery outcome a consumer reports as `SENT` or `FAILED`. |
| `commands/` | The commands one app sends to the app that owns the work, one JSON Schema per type (`data` only). The consumer owns the type; a producer implements it. See [commands/README.md](commands/README.md). |
| `messaging/` | The AMQP envelope, topology, delivery guarantees and the dead-letter runbook. See [messaging/README.md](messaging/README.md). |
| `permissions/` | One permission catalog per app (`billing.yaml`, `notification.yaml`, `email.yaml`, `platform.yaml`): `code`, `description`, `scopes`, `grantable_to_tenant_system`. Platform validates credential scopes against it and seeds roles from it; the file name is the app code. |
| `tokens/` | The claims of Platform-issued suite tokens for service clients and Tenant Systems. See [tokens/README.md](tokens/README.md). |

An app's OpenAPI document and event schemas are the executable owners of its API and event shapes; these files are
the documents every service reads to agree on them. See [docs/architecture.md](../docs/architecture.md).
