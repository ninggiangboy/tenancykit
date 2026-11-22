-- Outbound webhooks: the endpoints a Tenant registers, the deliveries they produce, and the attempt log.
--
-- A delivery stores the exact body that is signed and sent, so a retry is byte-identical. An in-flight delivery keeps
-- status IN_PROGRESS with next_attempt_at at the end of its lease, so a worker that dies mid-send simply lets the lease
-- run out and the row becomes due again.

create table webhook_endpoints (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    url               text        not null check (length(url) between 1 and 2048),
    description       text        check (length(description) <= 500),
    event_types       text[]      not null check (cardinality(event_types) between 1 and 100),
    -- DELETED keeps a removed endpoint's deliveries readable while the endpoint itself leaves every list.
    status            text        not null check (status in ('ACTIVE', 'DISABLED', 'DELETED')),
    -- The signing secret is stored only as envelope-encrypted ciphertext and is never returned after creation.
    key_id            text        not null,
    secret_ciphertext text        not null,
    version           bigint      not null default 1 check (version >= 1),
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    secret_rotated_at timestamptz
);

create index webhook_endpoints_tenant_idx on webhook_endpoints (tenant_id, created_at desc);

call enable_tenant_rls('webhook_endpoints');

create table webhook_deliveries (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    endpoint_id      uuid        not null references webhook_endpoints (id),
    event_id         uuid        not null,
    event_type       text        not null,
    body             text        not null,
    status           text        not null check (status in ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD')),
    attempts         integer     not null default 0 check (attempts >= 0),
    next_attempt_at  timestamptz not null,
    last_status_code integer,
    last_error       text,
    created_at       timestamptz not null,
    completed_at     timestamptz,
    check ((status in ('SUCCEEDED', 'DEAD')) = (completed_at is not null))
);

-- One delivery per event and endpoint, so a redelivered outbox row cannot send the same fact twice.
create unique index webhook_deliveries_event_idx on webhook_deliveries (endpoint_id, event_id);
create index webhook_deliveries_due_idx on webhook_deliveries (next_attempt_at)
    where status in ('PENDING', 'IN_PROGRESS');
create index webhook_deliveries_endpoint_idx on webhook_deliveries (endpoint_id, id desc);

call enable_tenant_rls('webhook_deliveries');

-- The attempt log is append-only: what was tried and what came back is evidence.
create table webhook_delivery_attempts (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    delivery_id      uuid        not null references webhook_deliveries (id),
    attempt_no       integer     not null check (attempt_no >= 1),
    status_code      integer,
    error            text,
    response_excerpt text,
    latency_ms       integer     not null check (latency_ms >= 0),
    attempted_at     timestamptz not null,
    unique (delivery_id, attempt_no)
);

call enable_tenant_rls('webhook_delivery_attempts');

revoke update, delete on webhook_delivery_attempts from booking_app;
