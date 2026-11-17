#!/usr/bin/env bash
# Proves the suite's isolation: no service can reach another service's database, and no service can publish to
# another service's exchange. Each attempt must fail; a success is the failure of this script.
#
# Run against a running `docker compose` deployment, from the repository root. It reads the credentials from `.env`
# (the same file compose reads). Requirements: docker compose.
set -euo pipefail

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

step() {
  echo "== $*"
}

# psql as one service's app role against one database, from a throwaway client container on the compose network.
psql_as() {
  local user="$1" password="$2" database="$3"
  docker compose run --rm --no-deps --entrypoint sh postgres -c \
    "PGPASSWORD='${password}' psql --no-psqlrc --quiet --tuples-only \
      --host postgres --username ${user} --dbname ${database} --command 'select 1'" > /dev/null 2>&1
}

require_refused() {
  local description="$1"
  shift
  if "$@" > /dev/null 2>&1; then
    fail "${description} unexpectedly succeeded"
  fi
  echo "refused: ${description}"
}

# One SQL statement as a service's app role, with app.tenant_id bound for the transaction, because row-level security
# reads the tenant from the connection and not from the statement.
sql_as_tenant() {
  local user="$1" password="$2" database="$3" tenant="$4" statement="$5"
  docker compose run --rm --no-deps --entrypoint sh postgres -c \
    "PGPASSWORD='${password}' psql --no-psqlrc --quiet --tuples-only --no-align \
      --host postgres --username ${user} --dbname ${database} \
      --command \"begin; select set_config('app.tenant_id', '${tenant}', true); ${statement}; commit;\"" 2>/dev/null |
    tr -d '[:space:]'
}

# One statement as the database owner, which bypasses row-level security and is how a fixture is seeded.
sql_as_owner() {
  local database="$1" statement="$2"
  docker compose run --rm --no-deps --entrypoint sh postgres -c \
    "PGPASSWORD='${POSTGRES_SUPERUSER_PASSWORD:?}' psql --no-psqlrc --quiet --tuples-only --no-align \
      --host postgres --username postgres --dbname ${database} --command \"${statement}\"" 2>/dev/null |
    tr -d '[:space:]'
}

# ------------------------------ databases ------------------------------
step "cross-database access must be refused"
# billing_app may reach only billing. Every role exists on the same server, so a connect to another database is the
# exact escalation `revoke connect on database ... from public` prevents.
require_refused "billing_app connecting to platform" psql_as billing_app "${BILLING_DB_PASSWORD:?}" platform
require_refused "billing_app connecting to tenant" psql_as billing_app "${BILLING_DB_PASSWORD:?}" tenant
require_refused "billing_app connecting to mailer" psql_as billing_app "${BILLING_DB_PASSWORD:?}" mailer
require_refused "platform_app connecting to billing" psql_as platform_app "${PLATFORM_DB_PASSWORD:?}" billing
require_refused "platform_app connecting to tenant" psql_as platform_app "${PLATFORM_DB_PASSWORD:?}" tenant
require_refused "platform_app connecting to mailer" psql_as platform_app "${PLATFORM_DB_PASSWORD:?}" mailer
require_refused "tenant_app connecting to billing" psql_as tenant_app "${TENANT_DB_PASSWORD:?}" billing
require_refused "tenant_app connecting to platform" psql_as tenant_app "${TENANT_DB_PASSWORD:?}" platform
require_refused "tenant_app connecting to mailer" psql_as tenant_app "${TENANT_DB_PASSWORD:?}" mailer
require_refused "mailer_app connecting to billing" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" billing
require_refused "mailer_app connecting to platform" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" platform
require_refused "mailer_app connecting to tenant" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" tenant
require_refused "billing_app connecting to notification" psql_as billing_app "${BILLING_DB_PASSWORD:?}" notification
require_refused "platform_app connecting to notification" psql_as platform_app "${PLATFORM_DB_PASSWORD:?}" notification
require_refused "tenant_app connecting to notification" psql_as tenant_app "${TENANT_DB_PASSWORD:?}" notification
require_refused "mailer_app connecting to notification" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" notification
require_refused "notification_app connecting to billing" psql_as notification_app "${NOTIFICATION_DB_PASSWORD:?}" billing
require_refused "notification_app connecting to platform" psql_as notification_app "${NOTIFICATION_DB_PASSWORD:?}" platform
require_refused "notification_app connecting to tenant" psql_as notification_app "${NOTIFICATION_DB_PASSWORD:?}" tenant
require_refused "notification_app connecting to mailer" psql_as notification_app "${NOTIFICATION_DB_PASSWORD:?}" mailer
require_refused "mailroom_app connecting to billing" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" billing
require_refused "mailroom_app connecting to platform" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" platform
require_refused "mailroom_app connecting to tenant" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" tenant
require_refused "mailroom_app connecting to mailer" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" mailer
require_refused "mailroom_app connecting to notification" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" notification
require_refused "notification_app connecting to mailroom" psql_as notification_app "${NOTIFICATION_DB_PASSWORD:?}" mailroom
require_refused "mailer_app connecting to mailroom" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" mailroom
require_refused "survey_app connecting to billing" psql_as survey_app "${SURVEY_DB_PASSWORD:?}" billing
require_refused "survey_app connecting to platform" psql_as survey_app "${SURVEY_DB_PASSWORD:?}" platform
require_refused "survey_app connecting to mailroom" psql_as survey_app "${SURVEY_DB_PASSWORD:?}" mailroom
require_refused "billing_app connecting to survey" psql_as billing_app "${BILLING_DB_PASSWORD:?}" survey
require_refused "booking_app connecting to billing" psql_as booking_app "${BOOKING_DB_PASSWORD:?}" billing
require_refused "booking_app connecting to platform" psql_as booking_app "${BOOKING_DB_PASSWORD:?}" platform
require_refused "booking_app connecting to survey" psql_as booking_app "${BOOKING_DB_PASSWORD:?}" survey
require_refused "billing_app connecting to booking" psql_as billing_app "${BILLING_DB_PASSWORD:?}" booking
require_refused "survey_app connecting to booking" psql_as survey_app "${SURVEY_DB_PASSWORD:?}" booking
# crm and loyalty each own one database. Their roles reach nothing else, and no other role reaches theirs.
require_refused "crm_app connecting to loyalty" psql_as crm_app "${CRM_DB_PASSWORD:?}" loyalty
require_refused "crm_app connecting to platform" psql_as crm_app "${CRM_DB_PASSWORD:?}" platform
require_refused "crm_app connecting to billing" psql_as crm_app "${CRM_DB_PASSWORD:?}" billing
require_refused "crm_app connecting to survey" psql_as crm_app "${CRM_DB_PASSWORD:?}" survey
require_refused "loyalty_app connecting to crm" psql_as loyalty_app "${LOYALTY_DB_PASSWORD:?}" crm
require_refused "loyalty_app connecting to platform" psql_as loyalty_app "${LOYALTY_DB_PASSWORD:?}" platform
require_refused "loyalty_app connecting to billing" psql_as loyalty_app "${LOYALTY_DB_PASSWORD:?}" billing
require_refused "loyalty_app connecting to survey" psql_as loyalty_app "${LOYALTY_DB_PASSWORD:?}" survey
require_refused "billing_app connecting to crm" psql_as billing_app "${BILLING_DB_PASSWORD:?}" crm
require_refused "billing_app connecting to loyalty" psql_as billing_app "${BILLING_DB_PASSWORD:?}" loyalty
require_refused "platform_app connecting to crm" psql_as platform_app "${PLATFORM_DB_PASSWORD:?}" crm
require_refused "platform_app connecting to loyalty" psql_as platform_app "${PLATFORM_DB_PASSWORD:?}" loyalty
require_refused "mailroom_app connecting to crm" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" crm
require_refused "mailroom_app connecting to loyalty" psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" loyalty
require_refused "mailer_app connecting to crm" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" crm
require_refused "mailer_app connecting to loyalty" psql_as mailer_app "${MAILER_DB_PASSWORD:?}" loyalty

# ------------------------------ broker ------------------------------
step "cross-exchange publishing must be refused"
# What the broker enforces is a permission triple per user: publishing checks `write` on the exchange, consuming checks
# `read` on the queue, and binding checks `write` on the queue plus `read` on the source exchange. These reads assert the
# triples themselves, because a publish attempt cannot: `rabbitmqadmin` 4.x publishes only to queues and streams, and an
# app user carries no management tag, so a publish it makes would be refused for the wrong reason.
BROKER="${BROKER_URL:-http://localhost:15672}"

broker_permissions() {
  curl --silent --fail-with-body --user "${RABBITMQ_ADMIN_USER:?}:${RABBITMQ_ADMIN_PASSWORD:?}" \
    "${BROKER}/api/permissions" | jq -r --arg user "$1" --arg part "$2" '.[] | select(.user == $user) | .[$part]'
}

# Every business exchange of another app, and the suite's delivery infrastructure. No grant may name one of these.
FOREIGN_EXCHANGES="billing.events survey.events attribution.events booking.events workflow.events mailer.events mailer.commands"

# The grant a user's `write` may name: only what its own app declares. This is asserted as an exact match, so a widened
# grant fails here rather than passing as "still refused".
require_only() {
  local user="$1" part="$2" expected="$3" actual
  actual=$(broker_permissions "${user}" "${part}")
  if [ -z "${actual}" ]; then
    fail "the ${user} user has no .${part} grant at all; the broker declaration did not run"
  fi
  if [ "${actual}" != "${expected}" ]; then
    fail "the ${user} user's ${part} permission is '${actual}', expected '${expected}'"
  fi
  echo "refused: the ${user} user may ${part} nothing outside '${expected}'"
}

# The suite rule, read off the same triples for every user: no grant names another app's business exchange, which is
# why a bind to one is refused by the broker and not by convention.
require_no_foreign_exchange() {
  local user="$1" part granted foreign
  for part in configure write read; do
    granted=$(broker_permissions "${user}" "${part}")
    for foreign in ${FOREIGN_EXCHANGES}; do
      case "${granted}" in
        *"${foreign}"*)
          # A producer's own exchange is not foreign to it; the caller's declaration is the judge, and only crm, loyalty,
          # booking, attribution and workflow are asserted here.
          case "${user}:${foreign}" in
            billing:billing.events|survey:survey.events|booking:booking.events|attribution:attribution.events|workflow:workflow.events|mailer:mailer.events|mailer:mailer.commands|notification:mailer.commands|mailroom:mailer.commands|mailroom:mailer.events) ;;
            *) fail "the ${user} user's ${part} permission names the foreign exchange ${foreign}: ${granted}" ;;
          esac
          ;;
      esac
    done
  done
  echo "refused: the ${user} user names no other app's business exchange in any grant"
}

for user in platform billing mailer notification mailroom survey booking workflow; do
  require_no_foreign_exchange "${user}"
done

# The two new apps own one queue each and no exchange, so their three grants are exactly their own names plus the
# dead-letter exchange they bind to, and `read` additionally covers platform.events (a bind checks read on the source).
require_only crm configure '^(crm\.platform-events|crm\.platform-events\.dlq|suite\.dlx)$'
require_only crm write '^(crm\.platform-events|crm\.platform-events\.dlq|suite\.dlx)$'
require_only crm read '^(platform\.events|crm\.platform-events|crm\.platform-events\.dlq|suite\.dlx)$'
require_only loyalty configure '^(loyalty\.platform-events|loyalty\.platform-events\.dlq|suite\.dlx)$'
require_only loyalty write '^(loyalty\.platform-events|loyalty\.platform-events\.dlq|suite\.dlx)$'
require_only loyalty read '^(platform\.events|loyalty\.platform-events|loyalty\.platform-events\.dlq|suite\.dlx)$'

# A positive control, so a script that proved only denials cannot pass while the declaration silently did nothing: each
# new app's queue must exist once its worker has started, and its own user must be able to read it. The queue is declared
# by the app at startup, not by the user script, so this also proves the app reached the broker.
for queue in crm.platform-events loyalty.platform-events; do
  if [ "$(curl --silent --user "${RABBITMQ_ADMIN_USER}:${RABBITMQ_ADMIN_PASSWORD}" \
    "${BROKER}/api/queues/%2F/${queue}" | jq -r '.name // empty')" != "${queue}" ]; then
    fail "the broker declares no queue ${queue}; its app did not start or did not bind it"
  fi
done
echo "allowed: both new apps' queues are declared on the broker"

# ------------------------------ row-level security ------------------------------
# The two tenant-scoping checks below read tables the mailer and notification migrations create, so they only run when
# those apps are part of the running stack. A partial stack (for example while another plan's app is mid-change) skips
# them and says so, rather than failing on a table that does not exist yet.
if [ -z "$(sql_as_owner mailer "select to_regclass('public.mailer_tenants')" 2>/dev/null)" ]; then
  echo "skipped: the mailer schema is not migrated, so its tenant-scoping check did not run"
else
step "a Platform user's notification row stays inside the Platform tenant"
# Now that a Platform user is a notification recipient, their rows live under the reserved Platform tenant id. The
# point of this check is that this is still only a tenant: an ordinary tenant scope must not see the row, which is the
# same policy that keeps one tenant out of another's inbox.
PLATFORM_TENANT_ID="00000000-0000-7000-8000-000000000001"
OTHER_TENANT_ID="11111111-1111-7111-8111-111111111111"

mailer_tenants_has_platform=$(
  sql_as_owner mailer "select count(*) from mailer_tenants where tenant_id = '${PLATFORM_TENANT_ID}'"
)
if [ "${mailer_tenants_has_platform}" != "1" ]; then
  fail "mailer_tenants has no row for the Platform tenant; a Platform user's email would never be delivered"
fi
echo "allowed: the Platform tenant is registered in mailer_tenants"

sql_as_owner notification "insert into notifications (
    id, tenant_id, user_id, type, source_event_id, source_event_type, template_key, template_version, locale,
    title, body, link, created_at)
  values ('22222222-2222-7222-8222-222222222222', '${PLATFORM_TENANT_ID}', '33333333-3333-7333-8333-333333333333',
    'ACCESS_GRANTED', '44444444-4444-7444-8444-444444444444', 'user-access.changed', 'access-granted', 0, 'en',
    'T', 'B', '/notification', now())
  on conflict do nothing" > /dev/null

visible_from_platform=$(
  sql_as_tenant notification_app "${NOTIFICATION_DB_PASSWORD:?}" notification "${PLATFORM_TENANT_ID}" \
    "select count(*) from notifications"
)
visible_from_another=$(
  sql_as_tenant notification_app "${NOTIFICATION_DB_PASSWORD:?}" notification "${OTHER_TENANT_ID}" \
    "select count(*) from notifications"
)
[ "${visible_from_platform}" = "1" ] ||
  fail "the Platform tenant cannot read its own notification row (saw ${visible_from_platform})"
[ "${visible_from_another}" = "0" ] ||
  fail "another tenant can read the Platform user's notification row (saw ${visible_from_another})"
sql_as_owner notification "delete from notifications where id = '22222222-2222-7222-8222-222222222222'" > /dev/null
echo "refused: another tenant reading the Platform user's notification row"

# The email ledger is a tenant's own data: a row under one tenant is invisible to another even though both connect as
# the same app role. This is the same policy every email table uses.
sql_as_owner mailroom "insert into emails (
    id, tenant_id, kind, to_address, from_key, subject, status, request_id, created_at, updated_at)
  values ('55555555-5555-7555-8555-555555555555', '${PLATFORM_TENANT_ID}', 'TRANSACTIONAL', 'a@example.com',
    'noreply', 'T', 'QUEUED', 'r', now(), now())
  on conflict do nothing" > /dev/null

mailroom_visible_from_platform=$(
  sql_as_tenant mailroom_app "${MAILROOM_DB_PASSWORD:?}" mailroom "${PLATFORM_TENANT_ID}" \
    "select count(*) from emails"
)
mailroom_visible_from_another=$(
  sql_as_tenant mailroom_app "${MAILROOM_DB_PASSWORD:?}" mailroom "${OTHER_TENANT_ID}" \
    "select count(*) from emails"
)
[ "${mailroom_visible_from_platform}" = "1" ] ||
  fail "the Platform tenant cannot read its own email row (saw ${mailroom_visible_from_platform})"
[ "${mailroom_visible_from_another}" = "0" ] ||
  fail "another tenant can read the Platform tenant's email row (saw ${mailroom_visible_from_another})"
sql_as_owner mailroom "delete from emails where id = '55555555-5555-7555-8555-555555555555'" > /dev/null
echo "refused: another tenant reading the Platform tenant's email row"

fi

# ------------------------------ a control ------------------------------
step "a service may still reach its own database"
# A control that proves the refusals above are about scope, not about a broken client. It runs for the apps whose
# database exists in this stack: an app another plan has not migrated yet has no database to reach, which is stated
# rather than counted as a failure of isolation.
own_database() {
  local app="$1" user="$2" password="$3" database="$4"
  if [ -z "$(docker compose run --rm --no-deps --entrypoint sh postgres -c \
    "PGPASSWORD='${POSTGRES_SUPERUSER_PASSWORD:?}' psql --no-psqlrc --quiet --tuples-only --no-align \
      --host postgres --username postgres --list" 2>/dev/null | awk -F'|' -v d="${database}" '$1 ~ d {print $1}' | tr -d ' ')" ]; then
    echo "skipped: ${app} has no database in this stack"
    return 0
  fi
  if ! psql_as "${user}" "${password}" "${database}"; then
    fail "${user} cannot reach its own database; the isolation checks are not meaningful"
  fi
  echo "allowed: ${user} connecting to ${database}"
}

own_database billing billing_app "${BILLING_DB_PASSWORD:?}" billing
own_database mailer mailer_app "${MAILER_DB_PASSWORD:?}" mailer
own_database notification notification_app "${NOTIFICATION_DB_PASSWORD:?}" notification
own_database mailroom mailroom_app "${MAILROOM_DB_PASSWORD:?}" mailroom
own_database survey survey_app "${SURVEY_DB_PASSWORD:?}" survey
own_database booking booking_app "${BOOKING_DB_PASSWORD:?}" booking
own_database workflow workflow_app "${WORKFLOW_DB_PASSWORD:?}" workflow
own_database crm crm_app "${CRM_DB_PASSWORD:?}" crm
own_database loyalty loyalty_app "${LOYALTY_DB_PASSWORD:?}" loyalty

echo "PASS: no service can reach another service's database or exchange"
