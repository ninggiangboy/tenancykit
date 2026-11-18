-- Centralized access control. Platform owns users, roles, their assignments and the permission catalog; every other
-- app receives one row per user through the user-access.changed event and never joins roles itself.

create table permissions (
    code                       text primary key,
    description                text    not null,
    scopes                     text[]  not null,
    grantable_to_tenant_system boolean not null
);

create table users (
    id               uuid primary key,
    tenant_id        uuid        not null references tenants (id),
    scope            text        not null check (scope in ('PLATFORM', 'TENANT')),
    keycloak_subject text        not null,
    email            text        not null,
    display_name     text        not null check (length(display_name) between 1 and 200),
    status           text        not null check (status in ('ACTIVE', 'DISABLED')),
    created_at       timestamptz not null,
    version          bigint      not null default 1,
    unique (tenant_id, email),
    unique (keycloak_subject)
);

call enable_tenant_rls('users');

create table roles (
    id         uuid primary key,
    tenant_id  uuid        not null references tenants (id),
    scope      text        not null check (scope in ('PLATFORM', 'TENANT')),
    name       text        not null check (length(name) between 1 and 100),
    system     boolean     not null,
    created_at timestamptz not null,
    version    bigint      not null default 1,
    unique (tenant_id, name)
);

call enable_tenant_rls('roles');

create table role_permissions (
    tenant_id       uuid not null references tenants (id),
    role_id         uuid not null references roles (id) on delete cascade,
    permission_code text not null,
    primary key (role_id, permission_code)
);

call enable_tenant_rls('role_permissions');

create table role_assignments (
    tenant_id uuid not null references tenants (id),
    user_id   uuid not null references users (id) on delete cascade,
    role_id   uuid not null references roles (id) on delete cascade,
    primary key (user_id, role_id)
);

call enable_tenant_rls('role_assignments');

create table user_permissions (
    tenant_id       uuid not null references tenants (id),
    user_id         uuid not null references users (id) on delete cascade,
    permission_code text not null,
    primary key (user_id, permission_code)
);

call enable_tenant_rls('user_permissions');

-- Platform administers users and roles for every Tenant, so the platform scope may read and write every row.
-- Policies are OR'ed: a tenant still sees only its own users and roles.
create policy platform_writer on users
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');
create policy platform_writer on roles
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');
create policy platform_writer on role_permissions
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');
create policy platform_writer on role_assignments
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');
create policy platform_writer on user_permissions
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');
