-- The message body, in its own table on purpose.
--
-- A body is personal data by definition, so it is kept on a short TTL and purged; the ledger row outlives it. Splitting
-- the two is what lets a delivery still be answered after its content is gone, and keeps a dead delivery resendable
-- for the window before the purge. `created_at` carries the purge index.
--
-- The column names are html_body/text_body rather than html/text so a generated jOOQ accessor is unambiguous and no
-- SQL reads as a type cast.

create table email_bodies (
    tenant_id   uuid        not null,
    delivery_id uuid        not null,
    subject     text        not null,
    reply_to    text,
    cc          text[]      not null default '{}',
    html_body   text,
    text_body   text,
    created_at  timestamptz not null,
    primary key (tenant_id, delivery_id),
    foreign key (tenant_id, delivery_id) references email_deliveries (tenant_id, id),
    check (html_body is not null or text_body is not null)
);

create index email_bodies_created_idx on email_bodies (created_at);
call enable_tenant_rls('email_bodies');
