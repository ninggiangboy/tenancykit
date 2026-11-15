-- The delivery ledger: one row per (tenant, request, recipient), plus its attempt history.
--
-- There is one row per recipient rather than one per request, so one bad address cannot hide the successful
-- deliveries beside it and a rejection of one recipient cannot roll back the others.
--
-- `completed_at` is present exactly for the two terminal states. Note the predicate includes DEAD: a constraint of
-- the form `status = 'SUCCEEDED' = (completed_at is not null)` would reject the DEAD write, making the terminal state
-- unreachable and turning every permanent rejection into an infinite resend loop.

-- The tenants this service has work for. Deliberately not row-level secured: the sweep has to read every tenant id
-- before it can assume any tenant's scope. It holds no personal data and no delivery content.
create table mailer_tenants (
    tenant_id  uuid primary key,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table email_deliveries (
    id                  uuid        primary key,
    tenant_id           uuid        not null,
    request_id          text        not null check (length(request_id) between 1 and 200),
    recipient           text        not null check (length(recipient) between 3 and 320),
    from_key            text        not null check (length(from_key) between 1 and 64),
    subject             text        not null check (length(subject) between 1 and 998),
    status              text        not null check (status in ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD')),
    attempts            integer     not null default 0 check (attempts >= 0),
    next_attempt_at     timestamptz,
    claimed_until       timestamptz,
    provider_message_id text,
    last_error          text,
    created_at          timestamptz not null,
    completed_at        timestamptz,
    -- The composite key the attempt and body tables reference, so neither can point at another tenant's row.
    unique (tenant_id, id),
    -- A producer that retries must not create a second email for the same request and recipient.
    unique (tenant_id, request_id, recipient),
    check ((status in ('SUCCEEDED', 'DEAD')) = (completed_at is not null))
);

-- The claim predicate: due in this tenant, in either live state, and not leased by another worker.
create index email_deliveries_due_idx on email_deliveries (tenant_id, status, next_attempt_at);
call enable_tenant_rls('email_deliveries');

-- What each attempt came back with. Append-only: it is evidence, and the ledger's status is only its last line.
create table email_delivery_attempts (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    delivery_id  uuid        not null,
    attempt_no   integer     not null check (attempt_no >= 1),
    outcome      text        not null check (outcome in ('ACCEPTED', 'REJECTED', 'TRANSIENT')),
    error        text,
    attempted_at timestamptz not null,
    unique (delivery_id, attempt_no),
    foreign key (tenant_id, delivery_id) references email_deliveries (tenant_id, id)
);

call enable_tenant_rls('email_delivery_attempts');
revoke update, delete on email_delivery_attempts from mailer_app;
