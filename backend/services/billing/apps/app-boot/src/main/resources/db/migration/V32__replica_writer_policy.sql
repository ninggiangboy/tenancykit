-- `tenant_systems` became a Platform-owned replica in V23, but unlike `user_grants` it kept only its tenant policy.
-- The replica writer upserts every Tenant's row under the Platform scope, so that write was refused and a rebuild
-- could never finish; the same event dead-lettered when it arrived live. The Platform scope may write these rows for
-- the same reason it may write `user_grants`: Billing only reads what Platform already owns.
drop policy if exists platform_writer on tenant_systems;
create policy platform_writer on tenant_systems
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');
