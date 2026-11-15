-- Templates (context `content`).
--
-- `email_templates` is the mutable pointer a tenant addresses by key; `email_template_revisions` holds immutable
-- bodies. A campaign pins a revision and every sent email records the revision it rendered, so editing a template
-- never rewrites history. `version` is the optimistic lock the API exposes as ETag/If-Match.
create table email_templates (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    key              text        not null
        check (length(key) between 1 and 64 and key ~ '^[a-z0-9][a-z0-9._-]*$'),
    name             text        not null check (length(name) between 1 and 200),
    current_revision integer     not null check (current_revision >= 1),
    version          bigint      not null default 1 check (version >= 1),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    unique (tenant_id, key)
);

call enable_tenant_rls('email_templates');

create table email_template_revisions (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    -- Deliberately no foreign key: deleting a template removes the pointer but keeps the immutable revisions, so a
    -- campaign that pinned a revision before the deletion still renders exactly what it planned.
    template_id uuid        not null,
    revision    integer     not null check (revision >= 1),
    subject     text        not null check (length(subject) between 1 and 998),
    html        text        not null check (length(html) <= 262144),
    text        text        check (text is null or length(text) <= 262144),
    created_at  timestamptz not null,
    unique (template_id, revision)
);

call enable_tenant_rls('email_template_revisions');

create index email_template_revisions_template_idx
    on email_template_revisions (tenant_id, template_id, revision desc);
