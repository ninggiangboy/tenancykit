-- Campaigns (context `sending`).
--
-- A campaign pins the template revision when it leaves DRAFT. `campaign_recipients` is the audience snapshot taken
-- once, in pages, when the campaign enters SENDING: a receiver added to the group later is not mailed, and one who
-- unsubscribes after the snapshot is re-checked at publish time. `claimed_until` is the lease that makes two workers
-- safe: a row whose lease expired is claimable again after a crash.
create table campaigns (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    name              text        not null check (length(name) between 1 and 200),
    status            text        not null
        check (status in ('DRAFT', 'SCHEDULED', 'SENDING', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    template_id       uuid        not null,
    template_revision integer     not null check (template_revision >= 1),
    target_type       text        not null check (target_type in ('GROUP', 'SEGMENT')),
    target_id         uuid        not null,
    from_key          text        not null check (length(from_key) between 1 and 64),
    from_name         text,
    reply_to          text,
    scheduled_at      timestamptz,
    started_at        timestamptz,
    completed_at      timestamptz,
    version           bigint      not null default 1 check (version >= 1),
    created_at        timestamptz not null,
    updated_at        timestamptz not null
);

create index campaigns_due_idx on campaigns (scheduled_at) where status = 'SCHEDULED';
create index campaigns_sending_idx on campaigns (tenant_id) where status = 'SENDING';
call enable_tenant_rls('campaigns');

create table campaign_recipients (
    tenant_id     uuid        not null,
    campaign_id   uuid        not null references campaigns (id) on delete cascade,
    receiver_id   uuid        not null,
    email_id      uuid,
    state         text        not null check (state in ('PENDING', 'PUBLISHED', 'SKIPPED')),
    claimed_until timestamptz,
    primary key (campaign_id, receiver_id)
);

create index campaign_recipients_pending_idx
    on campaign_recipients (campaign_id, receiver_id) where state = 'PENDING';
call enable_tenant_rls('campaign_recipients');
