-- The authoring tables: the survey pointer, its editable draft, the immutable published versions and the lineage a
-- retyped question leaves behind. Every table is tenant scoped and protected by the baseline's RLS template.
--
-- A survey carries the optimistic-lock version the lifecycle endpoints answer as an ETag; its draft carries its own,
-- because editing the document and publishing it are separate decisions a client compares separately.

create table surveys (
    id                 uuid        primary key,
    tenant_id          uuid        not null,
    name               text        not null check (length(name) between 1 and 200),
    status             text        not null check (status in ('DRAFT', 'OPEN', 'CLOSED')),
    current_version_id uuid,
    version            bigint      not null default 1 check (version >= 1),
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    -- A draft has never been published, so it points at no version. A closed survey may have been closed before its
    -- first publish and still point at none.
    check (status <> 'DRAFT' or current_version_id is null)
);

create index surveys_tenant_idx on surveys (tenant_id, created_at, id);

call enable_tenant_rls('surveys');

-- One draft per survey, replaced in place under a compare-and-swap on `etag`.
create table survey_drafts (
    survey_id  uuid        primary key references surveys (id),
    tenant_id  uuid        not null,
    document   jsonb       not null,
    etag       bigint      not null default 1 check (etag >= 1),
    updated_at timestamptz not null,
    updated_by text
);

call enable_tenant_rls('survey_drafts');

-- A published version is immutable: nothing updates or deletes it, so a response recorded against it keeps rendering
-- and aggregating after the draft has moved on. The grant below is the enforcement, not a convention: a stray update
-- statement fails with a privilege error instead of silently rewriting history.
create table survey_versions (
    id           uuid        primary key,
    survey_id    uuid        not null references surveys (id),
    tenant_id    uuid        not null,
    version_no   integer     not null check (version_no >= 1),
    document     jsonb       not null,
    published_at timestamptz not null,
    published_by text,
    unique (survey_id, version_no)
);

create index survey_versions_survey_idx on survey_versions (tenant_id, survey_id, version_no desc);

call enable_tenant_rls('survey_versions');

revoke update, delete on survey_versions from survey_app;

-- The lineage a type change leaves: the new question id, the id it replaced and the first version that carries it.
-- Question, option, matrix row and column ids are otherwise stable across versions, which is what lets an aggregate
-- group one question over every version.
create table question_lineage (
    survey_id        uuid    not null references surveys (id),
    question_id      uuid    not null,
    tenant_id        uuid    not null,
    replaces         uuid    not null,
    first_version_no integer not null check (first_version_no >= 1),
    primary key (survey_id, question_id),
    -- An id is retyped at most once; the next retype replaces the replacement.
    unique (survey_id, replaces)
);

create index question_lineage_replaces_idx on question_lineage (tenant_id, survey_id, replaces);

call enable_tenant_rls('question_lineage');
