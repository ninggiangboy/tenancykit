-- Billing stops owning tenants, users, roles, tenant systems and credentials; it keeps a replica of Platform's data.
--
-- Old rows are not migrated (out of scope). The identity tables are dropped; the replica tables gain the `version`
-- column the version guard compares, and the two new tables the replica writes appear. `tenants`, `tenant_domains`
-- and `tenant_systems` keep their names so every existing foreign key and row-level-security policy stays valid.

drop table if exists api_credentials;
drop table if exists user_permissions;
drop table if exists role_assignments;
drop table if exists role_permissions;
drop table if exists roles;
drop table if exists permissions;
drop table if exists billing_users;
drop table if exists rate_limit_buckets;
drop function if exists try_acquire_rate_limit(text, double precision, double precision, integer);

-- The version is what a replica event is compared against; a local row starts at 1 like Platform's.
alter table tenants add column if not exists version bigint not null default 1;
alter table tenant_domains add column if not exists version bigint not null default 1;
alter table tenant_systems add column if not exists version bigint not null default 1;

-- One row per user, as Platform publishes it: the Keycloak subject and the billing:* codes that user holds.
create table if not exists user_grants (
    user_id          uuid primary key,
    tenant_id        uuid        not null references tenants (id),
    scope            text        not null check (scope in ('PLATFORM', 'TENANT')),
    keycloak_subject text        not null unique,
    email            text        not null,
    display_name     text        not null,
    status           text        not null check (status in ('ACTIVE', 'DISABLED')),
    permissions      text[]      not null default '{}',
    version          bigint      not null default 0
);

create index if not exists user_grants_tenant_idx on user_grants (tenant_id);
call enable_tenant_rls('user_grants');

-- Platform administers users for every Tenant, so the platform scope may write the replica rows too. Not a
-- business table: it holds what Platform already owns and Billing only reads.
create policy platform_writer on user_grants
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');

-- One row per (tenant, app): whether the tenant may use Billing at all.
create table if not exists app_entitlements (
    tenant_id uuid    not null references tenants (id),
    app_code  text    not null,
    enabled   boolean not null,
    version   bigint  not null default 0,
    primary key (tenant_id, app_code)
);
