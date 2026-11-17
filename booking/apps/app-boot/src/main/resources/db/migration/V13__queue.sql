-- The waiting room: insert-only tickets and one watermark row per target.
--
-- Ticket numbers come from one sequence shared by every target, so issuing a ticket is an insert and never a counter
-- update on a hot row. Admission advances the target's watermark; polling reads a cached watermark, never a ticket.
create sequence queue_ticket_seq;

create table queue_tickets (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    target_id   text        not null check (length(target_id) between 1 and 100),
    ticket_no   bigint      not null,
    customer_ref text       not null check (length(customer_ref) between 1 and 200),
    status      text        not null check (status in ('WAITING', 'ADMITTED', 'USED', 'EXPIRED', 'LEFT')),
    issued_at   timestamptz not null,
    admitted_at timestamptz,
    unique (tenant_id, ticket_no)
);

create index queue_tickets_target_idx on queue_tickets (tenant_id, target_id, status, ticket_no);
create unique index queue_tickets_active_idx on queue_tickets (tenant_id, target_id, customer_ref)
    where status in ('WAITING', 'ADMITTED');
call enable_tenant_rls('queue_tickets');

create table queue_state (
    tenant_id           uuid        not null,
    target_id           text        not null,
    watermark_ticket_no bigint      not null default 0,
    admit_per_second    integer     not null check (admit_per_second >= 1),
    admit_window_seconds integer    not null check (admit_window_seconds >= 1),
    updated_at          timestamptz not null,
    primary key (tenant_id, target_id)
);

call enable_tenant_rls('queue_state');
