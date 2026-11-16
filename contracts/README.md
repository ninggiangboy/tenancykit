# contracts

Documents the suite's services share, never code. The only code they share is the technical `core/`.

| Path | Owns |
|---|---|
| `openapi/billing.yaml` | Billing's REST API. Generated from the code and checked by a test; regenerate with `./gradlew :app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`. |
| `openapi/billing-tenant-system.yaml` | The same API filtered to the operations a Tenant System may call with its suite token, generated from the same document by the same test. Never edit it by hand. |
| `openapi/platform.yaml` | Platform's REST API. Generated from the code and checked by a test; regenerate with `./gradlew :apps:app-boot:test --tests '*PlatformOpenApiContractTest' -Dopenapi.update=true`. |
| `openapi/notification.yaml` | Notification's REST API. Generated from the code and checked by a test. |
| `openapi/mailroom.yaml` | Mailroom's REST API. A resource skeleton in phase 1, filled by each phase and checked against the controllers. |
| `openapi/survey.yaml` | Survey's REST API. A resource skeleton in phase 1, filled by each phase and checked against the controllers. |
| `openapi/attribution.yaml` | Attribution's REST API. A resource skeleton, filled by each phase and checked against the controllers. |
| `openapi/booking.yaml` | Booking's REST API, including the public gate under `/p/**`. A resource skeleton in phase 1, filled by each phase and checked against the controllers. |
| `events/billing/` | Billing's outbound events: one JSON Schema per type, plus how a webhook delivery is signed and verified. See [events/README.md](events/README.md). |
| `events/platform/` | Platform's replicated state-transfer events (`*.changed`). |
| `events/mailer/` | Mailer's outbound events: one JSON Schema per type, today `email-delivery.changed`, the terminal delivery outcome a consumer reports as `SENT` or `FAILED`. |
| `events/survey/` | Survey's outbound events: `survey-version.published`, `survey.closed` and `survey-response.submitted`. |
| `events/crm/` | crm's outbound events: `customer.created`, `order.recorded`, `order.refunded` and `customer.erased`, the customer and order facts crm sends to a Tenant System as signed webhooks. |
| `events/loyalty/` | loyalty's outbound events: the twelve enrolment, points, tier and voucher facts loyalty sends to a Tenant System as signed webhooks. |
| `events/booking/` | booking's outbound events: the ten hold, confirmation, reschedule, cancellation, expiry, no-show, waitlist and series facts booking sends to a Tenant System as signed `Booking-*` webhooks. |
| `survey/` | The version document schema, the expression schema and the behaviour vectors both the server and a future fill UI must pass. See [survey/README.md](survey/README.md). |
| `attribution/` | The attribution vectors the credit engine must reproduce and the ingest payload schemas. See [attribution/README.md](attribution/README.md). |
| `loyalty/` | The loyalty earn vectors the earn engine must reproduce: the vector schema and the hand-computed vectors for orders, returns, promotions, tiers and expiry. |
| `workflow/` | The workflow graph schema, the expression schema and the behaviour vectors the engine must reproduce. See [workflow/README.md](workflow/README.md). |
| `booking/` | The RFC 5545 recurrence vectors the expander must reproduce, with the vector schema and a note per case. See [booking/README.md](booking/README.md). |
| `commands/` | The commands one app sends to the app that owns the work, one JSON Schema per type (`data` only). The consumer owns the type; a producer implements it. See [commands/README.md](commands/README.md). |
| `messaging/` | The AMQP envelope, topology, delivery guarantees and the dead-letter runbook. See [messaging/README.md](messaging/README.md). |
| `permissions/` | One permission catalog per app (`billing.yaml`, `notification.yaml`, `mailroom.yaml`, `survey.yaml`, `attribution.yaml`, `booking.yaml`, `workflow.yaml`, `platform.yaml`): `code`, `description`, `scopes`, `grantable_to_tenant_system`. Platform validates credential scopes against it and seeds roles from it; the file name is the app code. |
| `tokens/` | The claims of Platform-issued suite tokens for service clients and Tenant Systems. See [tokens/README.md](tokens/README.md). |

An app's OpenAPI document and event schemas are the executable owners of its API and event shapes; these files are
the documents every service reads to agree on them. See [docs/architecture.md](../docs/architecture.md).
