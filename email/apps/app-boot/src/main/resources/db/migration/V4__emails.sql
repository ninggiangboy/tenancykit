-- The sent-email ledger (context `sending`).
--
-- `request_id` is the idempotency key handed to mailer, unique per tenant: a retried API call resolves to the same
-- row and the same mailer ledger. `body_html`/`body_text` are the only personal-data columns and the first thing
-- housekeeping removes; metadata and events survive the purge.
create table emails (
    id                  uuid        primary key,
    tenant_id           uuid        not null,
    kind                text        not null check (kind in ('TRANSACTIONAL', 'MARKETING')),
    campaign_id         uuid,
    receiver_id         uuid,
    to_address          text        not null check (length(to_address) between 3 and 320),
    from_key            text        not null check (length(from_key) between 1 and 64),
    from_name           text,
    reply_to            text,
    subject             text        not null check (length(subject) between 1 and 998 and subject !~ '[\r\n]'),
    template_id         uuid,
    template_revision   integer,
    status              text        not null check (status in ('QUEUED', 'SENT', 'FAILED', 'SKIPPED')),
    status_reason       text,
    request_id          text        not null check (length(request_id) between 1 and 200),
    body_html           text,
    body_text           text,
    body_purged_at      timestamptz,
    delivery_updated_at timestamptz,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    unique (tenant_id, request_id)
);

create index emails_status_idx on emails (tenant_id, status, created_at desc);
create index emails_campaign_idx on emails (tenant_id, campaign_id);
create index emails_purge_idx on emails (tenant_id, created_at) where body_purged_at is null;
call enable_tenant_rls('emails');

-- Insert-only: a sent email's history is append-only, and statistics are aggregated from it. No address, IP or user
-- agent is stored here.
create table email_events (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    email_id    uuid        not null references emails (id) on delete cascade,
    campaign_id uuid,
    type        text        not null check (type in ('SENT', 'FAILED', 'OPENED', 'CLICKED', 'UNSUBSCRIBED')),
    link_id     integer,
    occurred_at timestamptz not null
);

create index email_events_email_idx on email_events (tenant_id, email_id, type);
create index email_events_campaign_idx on email_events (tenant_id, campaign_id, type);
call enable_tenant_rls('email_events');
