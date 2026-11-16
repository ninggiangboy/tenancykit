-- The link and visit tables: how a respondent reaches a survey, and what the app learned about the arrival without
-- keeping anything that identifies the person.
--
-- A link is public (one per Tenant-named source) or personal (bulk, one per recipient, optionally expiring and single
-- use). A visit is one opening of a link: it records the link's source, the bounded UTM labels, the referrer's
-- registrable domain, a device class, a browser family and a GeoIP country, and stores only a hash of its own token.

create table survey_links (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    survey_id    uuid        not null references surveys (id),
    kind         text        not null check (kind in ('PUBLIC', 'PERSONAL')),
    source       text        not null check (length(source) between 1 and 100),
    external_ref text        check (external_ref is null or length(external_ref) between 1 and 200),
    expires_at   timestamptz,
    status       text        not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
    created_at   timestamptz not null,
    -- A personal link is the one that carries a recipient; a public one names only its source.
    check ((kind = 'PERSONAL') = (external_ref is not null))
);

-- One public link per source: the Tenant's own label is what a report groups by, so a second link for the same source
-- would split one report into two.
create unique index survey_links_public_source_idx
    on survey_links (survey_id, source) where kind = 'PUBLIC';

-- A personal link is looked up by the source and the recipient a Tenant imported.
create index survey_links_personal_idx
    on survey_links (tenant_id, survey_id, external_ref) where kind = 'PERSONAL';

create index survey_links_tenant_idx on survey_links (tenant_id, survey_id, created_at desc);

call enable_tenant_rls('survey_links');

-- One opening of one link. `token_hash` is SHA-256 of the opaque token the browser holds, so a database copy cannot be
-- replayed; the raw address, user agent string and referrer path never reach this table.
create table visits (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    survey_id        uuid        not null references surveys (id),
    link_id          uuid        not null references survey_links (id),
    -- The version this visit opened. A visit keeps it for its whole life, so publishing v2 never changes the questions
    -- under a respondent who is already answering.
    version_id       uuid        not null,
    version_no       integer     not null check (version_no >= 1),
    token_hash       bytea       not null,
    -- What the visit contributes to the funnel: STARTED and COMPLETED are server-side facts, OPENED is where it begins
    -- and ABANDONED is the sweep's verdict on one that stopped.
    source           text        not null,
    utm              jsonb       not null default '{}'::jsonb,
    referrer_domain  text,
    device_class     text        not null check (device_class in ('DESKTOP', 'MOBILE', 'TABLET', 'UNKNOWN')),
    browser_family   text        not null,
    country          text        check (country is null or length(country) = 2),
    traffic_class    text        not null check (traffic_class in ('HUMAN', 'BOT')),
    state            text        not null default 'OPENED'
        check (state in ('OPENED', 'STARTED', 'COMPLETED', 'ABANDONED')),
    -- Set at the first draft save, so a completed visit points at the response it produced.
    response_id      uuid,
    opened_at        timestamptz not null,
    started_at       timestamptz,
    last_activity_at timestamptz not null,
    completed_at     timestamptz
);

create unique index visits_token_hash_idx on visits (token_hash);

create index visits_survey_idx on visits (tenant_id, survey_id, opened_at desc);

-- The sweep reads only visits that are waiting to move; a partial index keeps it from scanning the finished ones.
create index visits_pending_idx on visits (tenant_id, last_activity_at)
    where state in ('OPENED', 'STARTED');

call enable_tenant_rls('visits');

-- A page view the browser reports. The funnel's abandonment page is approximate because these depend on the client
-- sending them; STARTED and COMPLETED come from server-side facts instead.
create table visit_page_events (
    id        uuid        primary key,
    tenant_id uuid        not null,
    visit_id  uuid        not null references visits (id),
    page_id   uuid        not null,
    viewed_at timestamptz not null
);

create index visit_page_events_visit_idx on visit_page_events (visit_id, viewed_at);

call enable_tenant_rls('visit_page_events');
