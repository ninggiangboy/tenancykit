#!/usr/bin/env bash
# Creates the suite's databases and per-service roles on the development PostgreSQL.
#
# One server keeps a laptop's memory down; isolation is enforced per database. Each service has an owner role
# (Flyway migrates as it, and it owns the tables) and an application role (the service connects as it, it owns
# nothing and cannot bypass row-level security). Only the owner and the app role of a database may connect to it;
# `revoke connect` from public closes the default every role shares, so billing_app cannot open platform even
# though both roles live on the same server.
#
# The postgres image runs this once, on an empty data directory, as its superuser; the passwords come from the
# environment.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
  -v platform_owner_password="${PLATFORM_MIGRATION_DB_PASSWORD:?set it}" \
  -v platform_app_password="${PLATFORM_DB_PASSWORD:?set it}" \
  -v billing_owner_password="${BILLING_MIGRATION_DB_PASSWORD:?set it}" \
  -v billing_app_password="${BILLING_DB_PASSWORD:?set it}" \
  -v tenant_owner_password="${TENANT_MIGRATION_DB_PASSWORD:?set it}" \
  -v tenant_app_password="${TENANT_DB_PASSWORD:?set it}" \
  --dbname postgres <<'SQL'
create role platform_owner login password :'platform_owner_password' nocreatedb nocreaterole;
create role platform_app login password :'platform_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role billing_owner login password :'billing_owner_password' nocreatedb nocreaterole;
create role billing_app login password :'billing_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role tenant_owner login password :'tenant_owner_password' nocreatedb nocreaterole;
create role tenant_app login password :'tenant_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create database platform owner platform_owner;
create database billing owner billing_owner;
create database tenant owner tenant_owner;

revoke connect, temporary on database platform from public;
revoke connect, temporary on database billing from public;
revoke connect, temporary on database tenant from public;

grant connect, temporary on database platform to platform_owner, platform_app;
grant connect, temporary on database billing to billing_owner, billing_app;
grant connect, temporary on database tenant to tenant_owner, tenant_app;

-- The app role creates nothing; Flyway, as the owner, grants the tables it creates. These defaults cover every
-- table a later migration adds without a per-table grant.
\connect platform
alter default privileges for role platform_owner in schema public
    grant select, insert, update, delete on tables to platform_app;
alter default privileges for role platform_owner in schema public
    grant usage, select on sequences to platform_app;

\connect billing
alter default privileges for role billing_owner in schema public
    grant select, insert, update, delete on tables to billing_app;
alter default privileges for role billing_owner in schema public
    grant usage, select on sequences to billing_app;

\connect tenant
alter default privileges for role tenant_owner in schema public
    grant select, insert, update, delete on tables to tenant_app;
alter default privileges for role tenant_owner in schema public
    grant usage, select on sequences to tenant_app;
SQL
