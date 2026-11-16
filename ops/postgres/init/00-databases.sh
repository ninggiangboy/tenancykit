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
  -v mailer_owner_password="${MAILER_MIGRATION_DB_PASSWORD:?set it}" \
  -v mailer_app_password="${MAILER_DB_PASSWORD:?set it}" \
  -v notification_owner_password="${NOTIFICATION_MIGRATION_DB_PASSWORD:?set it}" \
  -v notification_app_password="${NOTIFICATION_DB_PASSWORD:?set it}" \
  -v mailroom_owner_password="${MAILROOM_MIGRATION_DB_PASSWORD:?set it}" \
  -v mailroom_app_password="${MAILROOM_DB_PASSWORD:?set it}" \
  -v survey_owner_password="${SURVEY_MIGRATION_DB_PASSWORD:?set it}" \
  -v survey_app_password="${SURVEY_DB_PASSWORD:?set it}" \
  -v crm_owner_password="${CRM_MIGRATION_DB_PASSWORD:?set it}" \
  -v crm_app_password="${CRM_DB_PASSWORD:?set it}" \
  -v loyalty_owner_password="${LOYALTY_MIGRATION_DB_PASSWORD:?set it}" \
  -v loyalty_app_password="${LOYALTY_DB_PASSWORD:?set it}" \
  -v booking_owner_password="${BOOKING_MIGRATION_DB_PASSWORD:?set it}" \
  -v booking_app_password="${BOOKING_DB_PASSWORD:?set it}" \
  -v workflow_owner_password="${WORKFLOW_MIGRATION_DB_PASSWORD:?set it}" \
  -v workflow_app_password="${WORKFLOW_DB_PASSWORD:?set it}" \
  --dbname postgres <<'SQL'
create role platform_owner login password :'platform_owner_password' nocreatedb nocreaterole;
create role platform_app login password :'platform_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role billing_owner login password :'billing_owner_password' nocreatedb nocreaterole;
create role billing_app login password :'billing_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role tenant_owner login password :'tenant_owner_password' nocreatedb nocreaterole;
create role tenant_app login password :'tenant_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role mailer_owner login password :'mailer_owner_password' nocreatedb nocreaterole;
create role mailer_app login password :'mailer_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role notification_owner login password :'notification_owner_password' nocreatedb nocreaterole;
create role notification_app login password :'notification_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role mailroom_owner login password :'mailroom_owner_password' nocreatedb nocreaterole;
create role mailroom_app login password :'mailroom_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role survey_owner login password :'survey_owner_password' nocreatedb nocreaterole;
create role survey_app login password :'survey_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role crm_owner login password :'crm_owner_password' nocreatedb nocreaterole;
create role crm_app login password :'crm_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role loyalty_owner login password :'loyalty_owner_password' nocreatedb nocreaterole;
create role loyalty_app login password :'loyalty_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role booking_owner login password :'booking_owner_password' nocreatedb nocreaterole;
create role booking_app login password :'booking_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role workflow_owner login password :'workflow_owner_password' nocreatedb nocreaterole;
create role workflow_app login password :'workflow_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create database platform owner platform_owner;
create database billing owner billing_owner;
create database tenant owner tenant_owner;
create database mailer owner mailer_owner;
create database notification owner notification_owner;
create database mailroom owner mailroom_owner;
create database survey owner survey_owner;
create database crm owner crm_owner;
create database loyalty owner loyalty_owner;
create database booking owner booking_owner;
create database workflow owner workflow_owner;

revoke connect, temporary on database platform from public;
revoke connect, temporary on database billing from public;
revoke connect, temporary on database tenant from public;
revoke connect, temporary on database mailer from public;
revoke connect, temporary on database notification from public;
revoke connect, temporary on database mailroom from public;
revoke connect, temporary on database survey from public;
revoke connect, temporary on database crm from public;
revoke connect, temporary on database loyalty from public;
revoke connect, temporary on database booking from public;
revoke connect, temporary on database workflow from public;

grant connect, temporary on database platform to platform_owner, platform_app;
grant connect, temporary on database billing to billing_owner, billing_app;
grant connect, temporary on database tenant to tenant_owner, tenant_app;
grant connect, temporary on database mailer to mailer_owner, mailer_app;
grant connect, temporary on database notification to notification_owner, notification_app;
grant connect, temporary on database mailroom to mailroom_owner, mailroom_app;
grant connect, temporary on database survey to survey_owner, survey_app;
grant connect, temporary on database crm to crm_owner, crm_app;
grant connect, temporary on database loyalty to loyalty_owner, loyalty_app;
grant connect, temporary on database booking to booking_owner, booking_app;
grant connect, temporary on database workflow to workflow_owner, workflow_app;

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

\connect mailer
alter default privileges for role mailer_owner in schema public
    grant select, insert, update, delete on tables to mailer_app;
alter default privileges for role mailer_owner in schema public
    grant usage, select on sequences to mailer_app;

\connect notification
alter default privileges for role notification_owner in schema public
    grant select, insert, update, delete on tables to notification_app;
alter default privileges for role notification_owner in schema public
    grant usage, select on sequences to notification_app;

\connect mailroom
alter default privileges for role mailroom_owner in schema public
    grant select, insert, update, delete on tables to mailroom_app;
alter default privileges for role mailroom_owner in schema public
    grant usage, select on sequences to mailroom_app;

\connect survey
alter default privileges for role survey_owner in schema public
    grant select, insert, update, delete on tables to survey_app;
alter default privileges for role survey_owner in schema public
    grant usage, select on sequences to survey_app;

\connect crm
alter default privileges for role crm_owner in schema public
    grant select, insert, update, delete on tables to crm_app;
alter default privileges for role crm_owner in schema public
    grant usage, select on sequences to crm_app;

\connect loyalty
alter default privileges for role loyalty_owner in schema public
    grant select, insert, update, delete on tables to loyalty_app;
alter default privileges for role loyalty_owner in schema public
    grant usage, select on sequences to loyalty_app;

\connect booking
alter default privileges for role booking_owner in schema public
    grant select, insert, update, delete on tables to booking_app;
alter default privileges for role booking_owner in schema public
    grant usage, select on sequences to booking_app;

\connect workflow
alter default privileges for role workflow_owner in schema public
    grant select, insert, update, delete on tables to workflow_app;
alter default privileges for role workflow_owner in schema public
    grant usage, select on sequences to workflow_app;

SQL
