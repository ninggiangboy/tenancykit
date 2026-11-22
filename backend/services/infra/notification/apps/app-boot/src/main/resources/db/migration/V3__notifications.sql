-- The stored in-app notification, the tenant's settings and the user's preference. All three are tenant-owned and
-- row-level secured.
--
-- The unique key is the second dedupe layer, not a redundant one: the inbox stops a redelivered envelope, and this key
-- stops a *different* envelope describing the same transition (a retried publication with a new event id).
create table notifications (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    user_id          uuid        not null,
    type             text        not null,
    source_event_id  uuid        not null,
    source_event_type text       not null,
    template_key     text        not null,
    template_version integer     not null,
    locale           text        not null,
    title            text        not null,
    body             text        not null,
    link             text,
    read_at          timestamptz,
    created_at       timestamptz not null,
    unique (tenant_id, source_event_id, user_id, type)
);

-- The list query: newest first for one user, with the id as the cursor and the tiebreaker.
create index notifications_inbox_idx on notifications (tenant_id, user_id, created_at desc, id);
call enable_tenant_rls('notifications');

-- An absent row means "all five types enabled, locale en", so a tenant that never configured anything still gets
-- notifications. `enabled_types` is a list of NotificationType names.
create table notification_settings (
    tenant_id      uuid        primary key,
    default_locale text        not null default 'en' check (default_locale in ('en', 'vi')),
    enabled_types  text[]      not null,
    updated_at     timestamptz not null default now()
);

call enable_tenant_rls('notification_settings');

-- One row per user per tenant. `locale` is nullable: an absent preference falls through to the tenant default.
create table notification_preferences (
    tenant_id   uuid        not null,
    user_id     uuid        not null,
    locale      text        check (locale in ('en', 'vi')),
    muted_types text[]      not null default '{}',
    updated_at  timestamptz not null default now(),
    primary key (tenant_id, user_id)
);

call enable_tenant_rls('notification_preferences');
