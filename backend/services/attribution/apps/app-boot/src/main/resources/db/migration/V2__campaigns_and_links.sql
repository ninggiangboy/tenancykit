-- Campaigns, tracked links, their destinations and the click counter, plus the bulk-request ledger that makes a
-- repeated Idempotency-Key return the same personal links.
--
-- The link's slug is unique per tenant, so a redirect can resolve one from the host's tenant alone. A destination's
-- match is a small JSON object of device classes and country codes; the redirect reads it from cache, never a row.
-- link_counters exists apart from links because a capped link is updated conditionally on every click and must not
-- touch the link's own version.

create table campaigns (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    name              text        not null check (length(name) between 1 and 200),
    slug              text        not null check (slug ~ '^[a-z0-9][a-z0-9_-]{2,63}$'),
    channel           text,
    status            text        not null check (status in ('ACTIVE', 'ARCHIVED')),
    attribution_model text        not null check (attribution_model in ('FIRST', 'LAST', 'LINEAR')),
    window_days       integer     not null check (window_days between 1 and 90),
    currency          text        not null check (currency ~ '^[A-Z]{3}$'),
    starts_on         date,
    ends_on           date,
    version           bigint      not null default 1,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    check (ends_on is null or starts_on is null or ends_on >= starts_on)
);

create unique index campaigns_slug_idx on campaigns (tenant_id, slug);
create index campaigns_tenant_status_idx on campaigns (tenant_id, status);
-- The composite key a link's cross-tenant-safe foreign key references; it must exist before `links` is created.
alter table campaigns add constraint campaigns_tenant_id_id_key unique (tenant_id, id);
call enable_tenant_rls('campaigns');

create table links (
    id             uuid        primary key,
    tenant_id      uuid        not null,
    campaign_id    uuid,
    slug           text        not null check (slug ~ '^[a-z0-9][a-z0-9_-]{2,63}$'),
    kind           text        not null check (kind in ('STANDARD', 'PERSONAL')),
    external_ref   text        check (external_ref is null or length(external_ref) <= 128),
    status         text        not null check (status in ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    expires_at     timestamptz,
    max_clicks     bigint      check (max_clicks is null or max_clicks >= 1),
    password_hash  text,
    utm            jsonb       not null default '{}',
    version        bigint      not null default 1,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    -- A campaign belongs to the same tenant; the composite key makes a cross-tenant reference impossible.
    foreign key (tenant_id, campaign_id) references campaigns (tenant_id, id)
);

create unique index links_slug_idx on links (tenant_id, slug);
create index links_tenant_external_ref_idx on links (tenant_id, external_ref);
create index links_tenant_campaign_idx on links (tenant_id, campaign_id);
-- The composite key a destination and a counter's cross-tenant-safe foreign key references.
alter table links add constraint links_tenant_id_id_key unique (tenant_id, id);
call enable_tenant_rls('links');

create table link_destinations (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    link_id     uuid        not null,
    position    integer     not null check (position >= 0),
    url         text        not null check (length(url) between 1 and 2048),
    weight      integer     not null default 1 check (weight >= 1),
    match       jsonb       not null default '{}',
    is_fallback boolean     not null default false,
    foreign key (tenant_id, link_id) references links (tenant_id, id) on delete cascade
);

create unique index link_destinations_position_idx on link_destinations (tenant_id, link_id, position);
-- Exactly one fallback per link is what the redirect relies on when no destination's match passes.
create unique index link_destinations_fallback_idx on link_destinations (tenant_id, link_id)
    where is_fallback;
call enable_tenant_rls('link_destinations');

create table link_counters (
    tenant_id      uuid   not null,
    link_id        uuid   not null,
    clicks_counted bigint not null default 0 check (clicks_counted >= 0),
    primary key (tenant_id, link_id),
    foreign key (tenant_id, link_id) references links (tenant_id, id) on delete cascade
);
call enable_tenant_rls('link_counters');

-- A bulk request's result, keyed by the caller's Idempotency-Key. A replay with the same body returns the stored
-- result; the same key with a different body is refused, so a retried request never mints a second set of links.
create table bulk_link_requests (
    tenant_id       uuid        not null,
    idempotency_key text        not null check (length(idempotency_key) between 1 and 255),
    request_hash    text        not null,
    result          jsonb       not null,
    created_at      timestamptz not null,
    primary key (tenant_id, idempotency_key)
);
call enable_tenant_rls('bulk_link_requests');
