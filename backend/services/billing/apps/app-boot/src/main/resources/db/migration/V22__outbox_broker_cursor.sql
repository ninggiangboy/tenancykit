-- The broker's own cursor on the outbox row. Publishing to RabbitMQ is a network call that cannot join the
-- database transaction, so it gets separate columns and never changes what the in-process relay sees.
alter table outbox
    add column published_at     timestamptz,
    add column publish_status   text        not null default 'PENDING'
        check (publish_status in ('PENDING', 'PUBLISHED', 'DEAD', 'SKIPPED')),
    add column publish_attempts integer     not null default 0,
    add column publish_next_at  timestamptz not null default now();

create index outbox_publish_pending_idx on outbox (publish_next_at)
    where published_at is null and publish_status = 'PENDING';

-- Rows written before this path existed are history: never replay them to the broker.
update outbox set publish_status = 'SKIPPED';
