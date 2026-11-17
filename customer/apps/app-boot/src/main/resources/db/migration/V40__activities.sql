-- The Tenant's activities: one thing a customer did, as the Tenant's own system reported it.
--
-- `type` is a free string the writer chooses, so a new source posts a new type without a change here; the pattern only
-- keeps it a usable key and bounded. `payload` is the writer's own object and is bounded in size by the application's
-- setting rather than by a column, because the bound also protects one webhook-sized request.
--
-- An activity is append-only: a correction is another activity, never an edit. `activity_ref` is the caller's own
-- reference: unique per tenant when present, so a retried call replays the row it already appended instead of
-- recording the same fact twice.
--
-- The keyset index is the timeline a customer is read back through, newest first with the id breaking a tie; the
-- unique key above serves the replay of one reference.

create table activities (
    tenant_id    uuid        not null,
    id           uuid        primary key,
    customer_id  uuid        not null references customers (id) on delete cascade,
    type         text        not null check (type ~ '^[a-z][a-z0-9._-]{0,63}$'),
    occurred_at  timestamptz not null,
    source       text        check (source is null or length(source) <= 64),
    payload      jsonb       not null default '{}'::jsonb check (jsonb_typeof(payload) = 'object'),
    activity_ref text        check (activity_ref is null or length(activity_ref) between 1 and 200),
    created_at   timestamptz not null
);

create unique index activities_activity_ref_idx on activities (tenant_id, activity_ref)
    where activity_ref is not null;

create index activities_customer_keyset_idx on activities (tenant_id, customer_id, occurred_at desc, id desc);

call enable_tenant_rls('activities');

-- The search over a customer's name is a trigram match, the shape the customers migration chose when it installed
-- pg_trgm; without this index a name search would read the whole table. The expression is spelled exactly as the
-- search writes it, because PostgreSQL only matches an index to an identical expression.
create index customers_display_name_trgm_idx on customers using gin (lower(display_name) gin_trgm_ops);

-- The same for an address prefix: the btree the lookup by identity uses is ordered by the collation, which a prefix
-- match cannot rely on, while a trigram index serves it whatever the collation is.
create index customers_email_trgm_idx on customers using gin (lower(email) gin_trgm_ops);
