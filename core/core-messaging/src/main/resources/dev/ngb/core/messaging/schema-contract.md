# The table contract core writes

`core-messaging` addresses these tables with plain SQL names, because a service's generated jOOQ schema belongs to
that service. Every service that uses `JooqOutboxAppender`, `JooqEventInbox`, `OutboxRelay` or `JooqAuditLog` must
create these columns in its own migration.

## `outbox`

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | primary key; the event id |
| `tenant_id` | `uuid` | not null; the tenant the event belongs to |
| `aggregate` | `text` | not null |
| `type` | `text` | not null; the event type |
| `payload` | `jsonb` | not null; the event body |
| `created_at` | `timestamptz` | not null |
| `status` | `text` | not null, default `PENDING`, one of `PENDING`, `DELIVERED`, `DEAD` |
| `attempts` | `integer` | not null, default 0 |
| `next_attempt_at` | `timestamptz` | not null |
| `last_error` | `text` | nullable |

Row-level security is on `tenant_id`. Keep a partial index for due rows:

```sql
create index outbox_pending_idx on outbox (next_attempt_at) where status = 'PENDING';
```

## `event_inbox`

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | `uuid` | not null |
| `consumer` | `text` | not null |
| `event_id` | `uuid` | not null |
| `processed_at` | `timestamptz` | not null, default `now()` |

`primary key (tenant_id, consumer, event_id)` turns a repeated delivery into a no-op insert. Row-level security is on
`tenant_id`.

## `audit_log`

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | primary key |
| `tenant_id` | `uuid` | not null; the reserved platform tenant for platform-scope actions |
| `actor` | `text` | not null |
| `action` | `text` | not null |
| `subject` | `text` | not null |
| `request_id` | `text` | nullable |
| `occurred_at` | `timestamptz` | not null |

Row-level security is on `tenant_id`, and the application role must not hold `update` or `delete` on the table, so
the audit trail stays append-only.
