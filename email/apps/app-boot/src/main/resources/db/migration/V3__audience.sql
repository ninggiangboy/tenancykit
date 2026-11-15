-- The tenant's own customers (context `audience`).
--
-- `email_normalized` is the lookup identity: lower-cased and trimmed, unique per tenant. `attributes` is the
-- receiver-controlled payload a template may read and a segment may filter; the GIN index is what keeps a large
-- tenant's segment query indexed.
create table receivers (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    email            text        not null check (length(email) between 3 and 320 and email !~ '[\r\n]'),
    email_normalized text        not null check (length(email_normalized) between 3 and 320),
    first_name       text        check (first_name is null or length(first_name) <= 200),
    last_name        text        check (last_name is null or length(last_name) <= 200),
    attributes       jsonb       not null default '{}'::jsonb,
    status           text        not null check (status in ('SUBSCRIBED', 'UNSUBSCRIBED')),
    version          bigint      not null default 1 check (version >= 1),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    unique (tenant_id, email_normalized)
);

create index receivers_attributes_idx on receivers using gin (attributes jsonb_path_ops);
call enable_tenant_rls('receivers');

create table receiver_groups (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    name       text        not null check (length(name) between 1 and 200),
    version    bigint      not null default 1 check (version >= 1),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, name)
);

call enable_tenant_rls('receiver_groups');

create table group_members (
    tenant_id   uuid        not null,
    group_id    uuid        not null references receiver_groups (id) on delete cascade,
    receiver_id uuid        not null references receivers (id) on delete cascade,
    added_at    timestamptz not null,
    primary key (group_id, receiver_id)
);

create index group_members_receiver_idx on group_members (tenant_id, receiver_id);
call enable_tenant_rls('group_members');

-- A suppression outlives the receiver: erasing a customer removes the row but keeps the address suppressed, so a
-- deleted-then-reimported address is still not mailed. `scope` is what separates consent withdrawal (MARKETING)
-- from an explicit block (ALL).
create table suppressions (
    tenant_id        uuid        not null,
    email_normalized text        not null check (length(email_normalized) between 3 and 320),
    scope            text        not null check (scope in ('MARKETING', 'ALL')),
    reason           text        not null check (reason in ('UNSUBSCRIBED', 'MANUAL')),
    created_at       timestamptz not null,
    primary key (tenant_id, email_normalized)
);

call enable_tenant_rls('suppressions');
