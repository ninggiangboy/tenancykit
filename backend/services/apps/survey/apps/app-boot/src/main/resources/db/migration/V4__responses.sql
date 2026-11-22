-- The response tables: what a respondent submitted, the typed index every aggregate reads, the counter the cap is
-- enforced by, and the uploads a file question references.
--
-- A response records the version the visit pinned, so a later publish never changes what a completed response means.
-- The submitted JSONB is the record an export shows verbatim; the typed `answers` rows are the index an aggregate,
-- filter, cross-tab and trend read.

create table responses (
    id                  uuid        primary key,
    tenant_id           uuid        not null,
    survey_id           uuid        not null references surveys (id),
    version_id          uuid        not null,
    link_id             uuid        not null references survey_links (id),
    visit_id            uuid        not null references visits (id),
    status              text        not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    locale              text,
    answers             jsonb       not null default '{}'::jsonb,
    vars                jsonb       not null default '{}'::jsonb,
    score               numeric(20, 6),
    -- The respondent's identity: a personal link's reference, else the value of the question marked identity.
    respondent_identity text        check (respondent_identity is null or length(respondent_identity) <= 320),
    external_ref        text        check (external_ref is null or length(external_ref) <= 200),
    source              text        not null,
    idempotency_key     text        check (idempotency_key is null or length(idempotency_key) <= 200),
    -- Set only for a personal link, because the partial unique index below is what makes single use hold under a race.
    personal_link_id    uuid        references survey_links (id),
    started_at          timestamptz not null,
    submitted_at        timestamptz,
    check ((status = 'COMPLETED') = (submitted_at is not null))
);

-- One response per visit: a visit is one respondent's session, so a second response from it would double count.
create unique index responses_visit_idx on responses (visit_id);

-- A repeated Idempotency-Key returns the first result rather than creating a second response.
create unique index responses_idempotency_idx on responses (tenant_id, idempotency_key)
    where idempotency_key is not null;

-- Single use in the database, not in a check-then-write: a personal link cannot complete twice even if two submits race.
create unique index responses_personal_link_idx on responses (personal_link_id)
    where status = 'COMPLETED' and personal_link_id is not null;

create index responses_survey_idx on responses (tenant_id, survey_id, submitted_at desc);

call enable_tenant_rls('responses');

-- One row per answer: a choice stores the option, a matrix answer one row per cell, a number its decimal. Every
-- aggregate, filter, cross-tab and trend reads here; the response's JSONB is what a display shows.
create table answers (
    id           uuid        primary key,
    response_id  uuid        not null references responses (id) on delete cascade,
    tenant_id    uuid        not null,
    survey_id    uuid        not null references surveys (id),
    version_id   uuid        not null,
    question_id  uuid        not null,
    option_id    uuid,
    row_id       uuid,
    num_value    numeric(20, 6),
    text_value   text,
    date_value   date,
    source       text        not null,
    submitted_at timestamptz not null
);

create index answers_response_idx on answers (response_id);
create index answers_question_option_idx on answers (tenant_id, survey_id, question_id, option_id);
create index answers_submitted_idx on answers (tenant_id, survey_id, submitted_at);

call enable_tenant_rls('answers');

-- The response cap, enforced by a conditional update rather than a read-then-write: fifty concurrent submits against a
-- cap of ten complete exactly ten, because the row lock serialises the increment.
create table survey_counters (
    survey_id  uuid    primary key references surveys (id),
    tenant_id  uuid    not null,
    completed  bigint  not null default 0 check (completed >= 0),
    cap        bigint  check (cap is null or cap >= 1)
);

call enable_tenant_rls('survey_counters');

-- A presigned upload: pending until a submit references it, then attached. The object key is derived from the tenant,
-- the survey and the upload id, so a filename a respondent chose is never a key.
create table uploads (
    id            uuid        primary key,
    tenant_id     uuid        not null,
    survey_id     uuid        not null references surveys (id),
    visit_id      uuid        not null references visits (id),
    question_id   uuid        not null,
    object_key    text        not null,
    declared_size bigint      not null check (declared_size >= 1),
    declared_mime text        not null,
    status        text        not null default 'PENDING' check (status in ('PENDING', 'ATTACHED')),
    created_at    timestamptz not null
);

create index uploads_visit_idx on uploads (visit_id, question_id);
-- The housekeeping sweep reads only pending uploads, so a partial index keeps it from scanning the attached ones.
create index uploads_pending_idx on uploads (created_at) where status = 'PENDING';

call enable_tenant_rls('uploads');
