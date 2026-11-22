-- Advertising cost per campaign and day, from a manual entry or an ad-platform import.
--
-- A cost row is keyed by (campaign, day, source, connection). A null connection means a manual row and is one value: the
-- unique index coalesces it, so a repeated manual set updates the same row instead of adding a second one. An import
-- writes only its own source, so a manual entry is never overwritten by a provider sync.
--
-- The campaign currency is the row's currency; an entry in another currency is refused (409) rather than converted,
-- because v1 performs no currency conversion.

create table spend_entries (
    id            uuid           primary key,
    tenant_id     uuid           not null,
    campaign_id   uuid           not null,
    day           date           not null,
    source        text           not null check (source in ('MANUAL', 'GOOGLE_ADS', 'META_ADS')),
    connection_id uuid,
    amount        numeric(19, 4) not null check (amount >= 0),
    currency      text           not null check (currency ~ '^[A-Z]{3}$'),
    impressions   bigint         check (impressions is null or impressions >= 0),
    clicks        bigint         check (clicks is null or clicks >= 0),
    updated_at    timestamptz    not null,
    -- A campaign belongs to the same tenant; the composite key makes a cross-tenant reference impossible.
    foreign key (tenant_id, campaign_id) references campaigns (tenant_id, id)
);

create unique index spend_entries_key on spend_entries (
    tenant_id, campaign_id, day, source, coalesce(connection_id, '00000000-0000-0000-0000-000000000000'::uuid));
create index spend_entries_range_idx on spend_entries (tenant_id, campaign_id, day);
call enable_tenant_rls('spend_entries');
