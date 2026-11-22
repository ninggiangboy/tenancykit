-- The transactional outbox: the delivery result mailer computes, written in the same transaction as the ledger
-- outcome and published later by the worker's relay. The column contract is core-messaging's schema-contract.md.
--
-- No foreign key on tenant_id. Mailer keeps no replica of Platform's tenant table, and a result about a tenant this
-- service has just registered must be writable before anything else references the id. Row-level security on
-- tenant_id is the isolation guarantee, exactly as it is for the ledger.
--
-- Both the in-process relay's columns and the broker relay's own cursor are present from the start: a short copy
-- fails on the first relay tick with `column publish_next_at does not exist`.
create table outbox (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    aggregate        text        not null,
    type             text        not null,
    payload          jsonb       not null,
    created_at       timestamptz not null,
    status           text        not null default 'PENDING' check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         integer     not null default 0,
    next_attempt_at  timestamptz not null,
    last_error       text,
    published_at     timestamptz,
    publish_status   text        not null default 'PENDING'
        check (publish_status in ('PENDING', 'PUBLISHED', 'DEAD', 'SKIPPED')),
    publish_attempts integer     not null default 0,
    publish_next_at  timestamptz not null default now()
);

create index outbox_pending_idx on outbox (next_attempt_at) where status = 'PENDING';
create index outbox_publish_pending_idx on outbox (publish_next_at)
    where published_at is null and publish_status = 'PENDING';
call enable_tenant_rls('outbox');
