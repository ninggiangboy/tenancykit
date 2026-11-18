-- The consumer's deduplication table, exactly as core-messaging's schema contract specifies.
--
-- Four columns, not three: JooqEventInbox inserts (tenant_id, consumer, event_id), and `processed_at` is what lets the
-- housekeeping job age rows out. A table created with three columns works until something needs to purge it, and adding
-- the column later is a migration on a large table.
--
-- No foreign key on tenant_id. A delivery about a tenant this service has not been told about is exactly the case the
-- inbox must survive; the value is asserted by the publisher, and row-level security on tenant_id is the isolation
-- guarantee.

create table event_inbox (
    tenant_id    uuid        not null,
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    primary key (tenant_id, consumer, event_id)
);

create index event_inbox_processed_idx on event_inbox (processed_at);
call enable_tenant_rls('event_inbox');
