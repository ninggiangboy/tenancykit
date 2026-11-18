-- One Tenant's hard limits. A row overrides the deployment defaults; an operator sets it, and no Tenant-facing API
-- exposes it. The table is tenant-scoped like every other, so a read sees only its own row.
create table workflow_tenant_quota (
    tenant_id            uuid        primary key,
    concurrent_instances integer     not null check (concurrent_instances > 0),
    active_definitions   integer     not null check (active_definitions > 0),
    updated_at           timestamptz not null
);

call enable_tenant_rls('workflow_tenant_quota');
