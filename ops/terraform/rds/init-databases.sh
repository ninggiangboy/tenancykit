#!/usr/bin/env bash
# RDS does not run ops/postgres/init/00-databases.sh, so this one-shot task recreates its model on the managed server:
# one owner role and one app role per service, a database per service owned by its owner, and the default grants
# Flyway relies on. Isolation is unchanged: `revoke connect ... from public` plus a grant to the two roles of the same
# database keeps one service out of another's database.
#
# Two details are RDS-specific. The master account creates roles and databases, but PostgreSQL refuses
# `alter default privileges for role <other>` unless the session can act as that role, so each owner connects as
# itself and sets its own defaults. The Keycloak database is owned by one role with no app role: Keycloak creates its
# schema at start and needs the privileges to do it.
#
# Passwords arrive as environment variables injected from Secrets Manager; nothing is committed.
set -euo pipefail

: "${PGHOST:?set PGHOST}" "${PGUSER:?set PGUSER}" "${PGPASSWORD:?set PGPASSWORD}"

psql --set=ON_ERROR_STOP=1 \
  --set=platform_owner_password="${PLATFORM_MIGRATION_DB_PASSWORD:?set the platform owner password}" \
  --set=platform_app_password="${PLATFORM_DB_PASSWORD:?set the platform app password}" \
  --set=billing_owner_password="${BILLING_MIGRATION_DB_PASSWORD:?set the billing owner password}" \
  --set=billing_app_password="${BILLING_DB_PASSWORD:?set the billing app password}" \
  --set=tenant_owner_password="${TENANT_MIGRATION_DB_PASSWORD:?set the tenant owner password}" \
  --set=tenant_app_password="${TENANT_DB_PASSWORD:?set the tenant app password}" \
  --set=mailer_owner_password="${MAILER_MIGRATION_DB_PASSWORD:?set the mailer owner password}" \
  --set=mailer_app_password="${MAILER_DB_PASSWORD:?set the mailer app password}" \
  --set=notification_owner_password="${NOTIFICATION_MIGRATION_DB_PASSWORD:?set the notification owner password}" \
  --set=notification_app_password="${NOTIFICATION_DB_PASSWORD:?set the notification app password}" \
  --set=mailroom_owner_password="${MAILROOM_MIGRATION_DB_PASSWORD:?set the mailroom owner password}" \
  --set=mailroom_app_password="${MAILROOM_DB_PASSWORD:?set the mailroom app password}" \
  --set=customer_owner_password="${CUSTOMER_MIGRATION_DB_PASSWORD:?set the customer owner password}" \
  --set=customer_app_password="${CUSTOMER_DB_PASSWORD:?set the customer app password}" \
  --set=loyalty_owner_password="${LOYALTY_MIGRATION_DB_PASSWORD:?set the loyalty owner password}" \
  --set=loyalty_app_password="${LOYALTY_DB_PASSWORD:?set the loyalty app password}" \
  --set=survey_owner_password="${SURVEY_MIGRATION_DB_PASSWORD:?set the survey owner password}" \
  --set=survey_app_password="${SURVEY_DB_PASSWORD:?set the survey app password}" \
  --set=booking_owner_password="${BOOKING_MIGRATION_DB_PASSWORD:?set the booking owner password}" \
  --set=booking_app_password="${BOOKING_DB_PASSWORD:?set the booking app password}" \
  --set=workflow_owner_password="${WORKFLOW_MIGRATION_DB_PASSWORD:?set the workflow owner password}" \
  --set=workflow_app_password="${WORKFLOW_DB_PASSWORD:?set the workflow app password}" \
  --set=attribution_owner_password="${ATTRIBUTION_MIGRATION_DB_PASSWORD:?set the attribution owner password}" \
  --set=attribution_app_password="${ATTRIBUTION_DB_PASSWORD:?set the attribution app password}" \
  --set=keycloak_owner_password="${KEYCLOAK_DB_PASSWORD:?set the keycloak owner password}" \
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

create role customer_owner login password :'customer_owner_password' nocreatedb nocreaterole;
create role customer_app login password :'customer_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role loyalty_owner login password :'loyalty_owner_password' nocreatedb nocreaterole;
create role loyalty_app login password :'loyalty_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role survey_owner login password :'survey_owner_password' nocreatedb nocreaterole;
create role survey_app login password :'survey_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role booking_owner login password :'booking_owner_password' nocreatedb nocreaterole;
create role booking_app login password :'booking_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role workflow_owner login password :'workflow_owner_password' nocreatedb nocreaterole;
create role workflow_app login password :'workflow_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role attribution_owner login password :'attribution_owner_password' nocreatedb nocreaterole;
create role attribution_app login password :'attribution_app_password' nosuperuser nocreatedb nocreaterole nobypassrls;

create role keycloak_owner login password :'keycloak_owner_password' nocreatedb nocreaterole;

create database platform owner platform_owner;
create database billing owner billing_owner;
create database tenant owner tenant_owner;
create database mailer owner mailer_owner;
create database notification owner notification_owner;
create database mailroom owner mailroom_owner;
create database customer owner customer_owner;
create database loyalty owner loyalty_owner;
create database survey owner survey_owner;
create database booking owner booking_owner;
create database workflow owner workflow_owner;
create database attribution owner attribution_owner;
create database keycloak owner keycloak_owner;

revoke connect, temporary on database platform from public;
revoke connect, temporary on database billing from public;
revoke connect, temporary on database tenant from public;
revoke connect, temporary on database mailer from public;
revoke connect, temporary on database notification from public;
revoke connect, temporary on database mailroom from public;
revoke connect, temporary on database customer from public;
revoke connect, temporary on database loyalty from public;
revoke connect, temporary on database survey from public;
revoke connect, temporary on database booking from public;
revoke connect, temporary on database workflow from public;
revoke connect, temporary on database attribution from public;
revoke connect, temporary on database keycloak from public;

grant connect, temporary on database platform to platform_owner, platform_app;
grant connect, temporary on database billing to billing_owner, billing_app;
grant connect, temporary on database tenant to tenant_owner, tenant_app;
grant connect, temporary on database mailer to mailer_owner, mailer_app;
grant connect, temporary on database notification to notification_owner, notification_app;
grant connect, temporary on database mailroom to mailroom_owner, mailroom_app;
grant connect, temporary on database customer to customer_owner, customer_app;
grant connect, temporary on database loyalty to loyalty_owner, loyalty_app;
grant connect, temporary on database survey to survey_owner, survey_app;
grant connect, temporary on database booking to booking_owner, booking_app;
grant connect, temporary on database workflow to workflow_owner, workflow_app;
grant connect, temporary on database attribution to attribution_owner, attribution_app;
grant connect, temporary on database keycloak to keycloak_owner;
SQL

apply_default_privileges() {
  local owner="$1" password="$2" database="$3" app="$4"
  PGPASSWORD="$password" psql --set=ON_ERROR_STOP=1 \
    --host "$PGHOST" --username "$owner" --dbname "$database" <<SQL
alter default privileges in schema public
    grant select, insert, update, delete on tables to $app;
alter default privileges in schema public
    grant usage, select on sequences to $app;
SQL
}

apply_default_privileges platform_owner "${PLATFORM_MIGRATION_DB_PASSWORD}" platform platform_app
apply_default_privileges billing_owner "${BILLING_MIGRATION_DB_PASSWORD}" billing billing_app
apply_default_privileges tenant_owner "${TENANT_MIGRATION_DB_PASSWORD}" tenant tenant_app
apply_default_privileges mailer_owner "${MAILER_MIGRATION_DB_PASSWORD}" mailer mailer_app
apply_default_privileges notification_owner "${NOTIFICATION_MIGRATION_DB_PASSWORD}" notification notification_app
apply_default_privileges mailroom_owner "${MAILROOM_MIGRATION_DB_PASSWORD}" mailroom mailroom_app
apply_default_privileges customer_owner "${CUSTOMER_MIGRATION_DB_PASSWORD}" customer customer_app
apply_default_privileges loyalty_owner "${LOYALTY_MIGRATION_DB_PASSWORD}" loyalty loyalty_app
apply_default_privileges survey_owner "${SURVEY_MIGRATION_DB_PASSWORD}" survey survey_app
apply_default_privileges booking_owner "${BOOKING_MIGRATION_DB_PASSWORD}" booking booking_app
apply_default_privileges workflow_owner "${WORKFLOW_MIGRATION_DB_PASSWORD}" workflow workflow_app
apply_default_privileges attribution_owner "${ATTRIBUTION_MIGRATION_DB_PASSWORD}" attribution attribution_app

echo "created platform, billing, tenant, mailer, notification, mailroom, customer, loyalty, survey, booking, workflow, attribution and keycloak databases with their owner and app roles"
