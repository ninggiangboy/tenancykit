-- Roles, the row-level-security template, and the grants every later migration relies on.
--
-- The migration owner (the role Flyway connects as) owns every table. The application connects as mailer_app, which
-- owns nothing and cannot bypass row-level security, so a mis-wired connection fails closed instead of reading across
-- tenants.

do $$
begin
    if not exists (select from pg_roles where rolname = 'mailer_app') then
        create role mailer_app login password '${appDbPassword}'
            nosuperuser nocreatedb nocreaterole nobypassrls;
    end if;
end
$$;

grant usage on schema public to mailer_app;
alter default privileges in schema public
    grant select, insert, update, delete on tables to mailer_app;
alter default privileges in schema public
    grant usage, select on sequences to mailer_app;

-- The tenant of the current transaction. set_config(..., true) is transaction-local, and after the first use
-- PostgreSQL reports an unset value as '' rather than NULL, hence the nullif.
create function app_tenant_id() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- Every tenant-scoped table calls this once. FORCE applies the policy to the table owner too.
create procedure enable_tenant_rls(tbl regclass)
    language plpgsql
as $$
begin
    execute format('alter table %s enable row level security', tbl);
    execute format('alter table %s force row level security', tbl);
    execute format(
        'create policy tenant_isolation on %s using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id())',
        tbl);
end
$$;
