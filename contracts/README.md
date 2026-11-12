# contracts

Documents that the two systems share, never code. The only code they share is the technical `core/`.

| Path | Owns |
|---|---|
| `openapi/billing.yaml` | The REST API. Generated from the code and checked by a test; regenerate with `./gradlew :app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`. |
| `openapi/billing-tenant-system.yaml` | The same API filtered to the operations a Tenant System may call with its API key, generated from the same document by the same test. Never edit it by hand. |
| `events/billing/` | Billing's outbound events: one JSON Schema per type, plus how a webhook delivery is signed and verified. See [events/README.md](events/README.md). |
| `events/platform/` | Platform's replicated state-transfer events (`*.changed`). |
| `commands/` | Commands one app sends to one owner; `notification.requested` is the first. |
| `messaging/` | The AMQP envelope, topology, delivery guarantees and the dead-letter runbook. See [messaging/README.md](messaging/README.md). |
| `permissions/` | One permission catalog per app: `code`, `description`, `scopes`, `grantable_to_tenant_system`. |
| `tokens/` | The claims of Platform-issued suite tokens for service clients and Tenant Systems. See [tokens/README.md](tokens/README.md). |

See [docs/architecture.md](../docs/architecture.md).
