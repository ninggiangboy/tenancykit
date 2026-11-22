-- The export ledger: the one table insight writes, because insight answers questions about responses and never changes
-- one. A delete goes through collection's use case; an export is insight's own record of a request it served.
--
-- Every other number insight reports is read from collection's own tables through its api package, so a response that
-- is deleted disappears from every aggregate without insight keeping a copy.

create table exports (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    survey_id    uuid        not null references surveys (id),
    status       text        not null default 'QUEUED' check (status in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    -- The filter the export was asked for, kept so the same request can be repeated and so a finished file explains
    -- what it holds. It never carries a respondent's answer, only the shape of the query.
    filter       jsonb       not null default '{}'::jsonb,
    object_key   text,
    rows         bigint,
    requested_by text,
    created_at   timestamptz not null,
    finished_at  timestamptz,
    -- A finished export names its object and the row count; a queued or failed one has neither.
    check (status <> 'DONE' or (object_key is not null and rows is not null))
);

create index exports_survey_idx on exports (tenant_id, survey_id, created_at desc);
-- The worker reads only exports waiting to run, so a partial index keeps it from scanning the finished ones.
create index exports_queue_idx on exports (created_at) where status = 'QUEUED';

call enable_tenant_rls('exports');
