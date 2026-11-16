-- The Tenant's own vocabulary for its customers: the typed fields it defines, the tags it groups customers by, and the
-- consent topics it records agreement to.
--
-- A definition's key and type are immutable, because a type change is a new field rather than an edit: the values
-- already stored keep the meaning they had when they were written. A definition is archived instead of deleted, so a
-- value stored for it stays readable and only new values are refused.
--
-- customers.fields holds the values keyed by definition key. The GIN index (jsonb_path_ops) serves the equality and
-- containment filters the customer list grows; the cap on the serialized size of the object is the application's.
--
-- consent_events is append-only: a revocation appends a row and never rewrites the grant it revokes, so the history
-- answers "what did this customer agree to, and when" without inference. source and evidence_ref are free text the
-- Tenant sends and can hold personal data, which is why erasure clears them.

create table field_definitions (
    tenant_id  uuid        not null,
    key        text        not null check (key ~ '^[a-z][a-z0-9_]{0,39}$'),
    label      text        not null check (length(label) between 1 and 200),
    type       text        not null check (type in ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'CHOICE')),
    choices    text[]      not null default '{}' check (cardinality(choices) <= 50),
    archived   boolean     not null default false,
    created_at timestamptz not null,
    primary key (tenant_id, key),
    -- Only a CHOICE carries values and a CHOICE carries at least one, so a stored definition always says what it accepts.
    check ((type = 'CHOICE') = (cardinality(choices) > 0))
);

call enable_tenant_rls('field_definitions');

alter table customers
    add column fields jsonb not null default '{}'::jsonb check (jsonb_typeof(fields) = 'object');

-- Phase 8's equality and containment filters over a field value read through this index; the default btree cannot.
create index customers_fields_idx on customers using gin (fields jsonb_path_ops);

create table customer_tags (
    tenant_id   uuid not null,
    customer_id uuid not null references customers (id) on delete cascade,
    tag         text not null check (tag ~ '^[a-z0-9][a-z0-9 _:-]{0,49}$'),
    primary key (tenant_id, customer_id, tag)
);

-- One customer's tags, in tag order, and the listing that counts a tenant's distinct tags and pages through them.
create index customer_tags_customer_idx on customer_tags (tenant_id, customer_id, tag);
create index customer_tags_tenant_tag_idx on customer_tags (tenant_id, tag);

call enable_tenant_rls('customer_tags');

create table consent_topics (
    tenant_id uuid    not null,
    key       text    not null check (key ~ '^[a-z][a-z0-9_]{0,39}$'),
    label     text    not null check (length(label) between 1 and 200),
    archived  boolean not null default false,
    primary key (tenant_id, key)
);

call enable_tenant_rls('consent_topics');

create table consent_events (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    customer_id  uuid        not null references customers (id) on delete cascade,
    topic_key    text        not null check (topic_key ~ '^[a-z][a-z0-9_]{0,39}$'),
    status       text        not null check (status in ('GRANTED', 'REVOKED')),
    occurred_at  timestamptz not null,
    recorded_at  timestamptz not null,
    source       text        check (source is null or length(source) <= 500),
    evidence_ref text        check (evidence_ref is null or length(evidence_ref) <= 500),
    consent_ref  text        check (consent_ref is null or length(consent_ref) between 1 and 200)
);

-- The caller's own reference makes a record replayable: one row per reference of one customer, whatever the retry.
create unique index consent_events_consent_ref_idx on consent_events (tenant_id, customer_id, consent_ref)
    where consent_ref is not null;

-- The history the customer is read back with, newest first; the id breaks a tie between two records of one instant.
create index consent_events_history_idx on consent_events (tenant_id, customer_id, occurred_at desc, id desc);

call enable_tenant_rls('consent_events');

-- A record is written once and never rewritten: the history of an agreement is evidence, so a revocation appends.
revoke update on consent_events from crm_app;
