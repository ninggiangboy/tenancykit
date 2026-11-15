-- The email half: a ledger row per (tenant, source event, type, user) and the transactional outbox that carries the
-- already-rendered `email.send` command to mailer.
--
-- `PUBLISHED` is this service's statement ("the command is on the broker"); it must never claim DELIVERED, because
-- nothing here knows what SES did. Mailer's ledger remains the only delivery truth.
create table email_dispatches (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    source_event_id  uuid        not null,
    notification_type text       not null,
    user_id          uuid        not null,
    recipient        text,
    request_id       text        not null,
    from_key         text        not null,
    template_key     text        not null,
    template_version integer     not null,
    locale           text        not null,
    subject          text,
    status           text        not null
        check (status in ('PENDING', 'PUBLISHED', 'SUPPRESSED_MUTED',
                          'SKIPPED_INACTIVE', 'SKIPPED_NO_ADDRESS', 'DEAD')),
    reason           text,
    created_at       timestamptz not null,
    published_at     timestamptz,
    unique (tenant_id, source_event_id, notification_type, user_id)
);

create index email_dispatches_user_type_idx
    on email_dispatches (tenant_id, user_id, notification_type, created_at desc);
call enable_tenant_rls('email_dispatches');

-- The outbox in its final shape: both the in-process relay's columns and the broker relay's own cursor. A short
-- `V2`-shaped copy fails on the first relay tick with `column publish_next_at does not exist`.
create table outbox (
    id               uuid        primary key,
    tenant_id        uuid        not null references tenants (id),
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
