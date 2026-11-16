-- A Tenant System's purpose is fixed at creation: GENERIC is a Tenant's own business system, WORKFLOW is the
-- dedicated system that drives the workflow app. Existing rows are backfilled by the default.
alter table tenant_systems
    add column purpose text not null default 'GENERIC'
        check (purpose in ('GENERIC', 'WORKFLOW'));
