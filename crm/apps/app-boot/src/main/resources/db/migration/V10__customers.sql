-- The Tenant's customers, keyed by the reference the Tenant's own system chose. crm generates its own id and never
-- matches a customer by phone or email: those are stored attributes with their own indexes for the search the list
-- endpoint grows later.
--
-- display_name is nullable on purpose. A customer is created by PUT alone, and an order or an activity may name an
-- unknown reference and create the customer with nothing but that reference, so "no name yet" is a state the table
-- has to hold. Every value that is present is bounded here as well as in the domain, so a bypass of the application
-- still cannot store a 10 KB name.
--
-- Search index decision, recorded here because it decides what the search may use later: pg_trgm is created by this
-- migration, which is the proof that the migration owner may install the extension. It is a trusted extension, so
-- the owner needs no superuser for it, and contains-search over lower(display_name) or lower(email) is therefore
-- available with a gin_trgm_ops index. What exists today is what this endpoint needs: the phone digits and the
-- lower-cased email for a lookup by identity, and the keyset index the customer list pages by. A prefix search over
-- lower(display_name) with text_pattern_ops is the fallback and is not needed.
create extension if not exists pg_trgm;

create table customers (
    tenant_id    uuid        not null,
    id           uuid        primary key,
    -- The Tenant's reference: unique per tenant, immutable, trimmed before it reaches here.
    external_ref text        not null check (length(external_ref) between 1 and 128),
    display_name text        check (display_name is null or length(display_name) between 1 and 200),
    -- The phone as the Tenant sent it; the digits are derived from it for a lookup that ignores formatting.
    phone        text        check (phone is null or length(phone) between 1 and 32),
    phone_digits text        check (phone_digits is null or phone_digits ~ '^[0-9]{1,32}$'),
    -- Stored lower-cased; the unique address of a person is not crm's business, so no unique index.
    email        text        check (email is null or length(email) <= 254),
    status       text        not null check (status in ('ACTIVE', 'ERASED')),
    version      bigint      not null default 1 check (version >= 1),
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    unique (tenant_id, external_ref)
);

create index customers_phone_digits_idx on customers (tenant_id, phone_digits);
create index customers_email_idx on customers (tenant_id, lower(email));
-- The customer list pages by the newest first and breaks ties on the id, so the cursor is a range scan on one index.
create index customers_keyset_idx on customers (tenant_id, created_at desc, id desc);

call enable_tenant_rls('customers');
