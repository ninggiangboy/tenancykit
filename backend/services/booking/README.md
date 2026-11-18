# booking

The suite's app for letting a Tenant sell time or places: resources with a zone and weekly hours, interval and capacity
offerings, holds, confirmations, reschedules, series, a waitlist, a waiting room and signed webhooks. It is a separate
Gradle build; its contexts, layers and gates are the same shape as every app's.

| Directory | Contents |
|---|---|
| `apps/app-boot` | The runnable app: wiring, the Platform replica, security, the tenant sweep, migrations and the generated OpenAPI contract |
| `contexts/platform` | A replica of the Platform data booking enforces, and the read ports over it |
| `contexts/catalog` | Resources, hours, exceptions, groups, offerings, blocks, session series and sessions |
| `contexts/inventory` | The two claim engines (interval and capacity), availability and resource assignment |
| `contexts/booking` | The `Booking` aggregate and its lifecycle, series and the waitlist |
| `contexts/gate` | Customer tokens, the public guard and the ordered waiting room |
| `contexts/webhook` | Endpoint registration, signing keys and deliveries |
| `libs/shared-core` | Booking's value objects and permission codes |
| `libs/jooq-schema` | The generated jOOQ schema, committed and checked against the migrations by a test |
| `testing/arch-fixtures` | The deliberately violating classes every architecture rule is proved against |

## Build

Needs JDK 25 and a Docker daemon for Testcontainers.

```bash
cd booking && ./gradlew check
```

`check` runs the ArchUnit and Spring Modulith gates, the per-migration jOOQ drift test, the row-level-security test and
the isolation gate that fails when this build depends on another service's code.

Regenerate the committed jOOQ schema after a migration:

```bash
./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true
```

## Run

`docker compose up -d --build --wait` from the repository root runs `booking-migrate`, `booking-api` and
`booking-worker` beside the rest of the suite. Booking is reached at
`<tenant-code>.<base-domain>/booking`, and its public gate at `/booking/p/...`.

A tenant can use booking only after its entitlement exists; Platform packages
`contracts/permissions/booking.yaml` at build time, so rebuild Platform before enabling the app for a tenant.

The design — engines, state machine, lock order, timing defaults and the Tenant's hold-charge-confirm recipe — is in
[docs/booking.md](../docs/booking.md).

## Verify

```bash
# the invariant check, as the schema owner, against the compose stack
ops/smoke/booking-invariants.sh

# the Tenant's end-to-end paths: catalog, gate hold, waiting room, waitlist, reschedule, series
ops/smoke/booking.sh

# the hold path under load; mint a customer token and name the seeded offering first
BOOKING_LOAD_TOKEN=... BOOKING_LOAD_OFFERING=... BOOKING_LOAD_ATTEMPTS=1000 ops/loadtest/booking.sh
```
