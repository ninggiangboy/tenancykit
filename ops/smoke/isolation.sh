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

# ------------------------------ broker ------------------------------
step "cross-exchange publishing must be refused"
# The billing broker user may not write to platform.events; the exchange exists, so only its permissions can stop it.
billing_publish_to_platform() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username billing --password "${BILLING_RABBITMQ_PASSWORD:?}" \
    publish exchange=platform.events routing_key=tenant.changed payload='{}' > /dev/null 2>&1
}

platform_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username platform --password "${PLATFORM_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

# Mailer consumes commands and publishes one event to `mailer.events`. Every other exchange must be refused, including
# `mailer.commands`: a service may not publish to the exchange it consumes from, or its results would look like
# commands. It also may not read another service's queue — a message there carries that service's work.
mailer_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

mailer_publish_to_platform() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    publish exchange=platform.events routing_key=tenant.changed payload='{}' > /dev/null 2>&1
}

mailer_publish_to_its_command_exchange() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    publish exchange=mailer.commands routing_key=email.send payload='{}' > /dev/null 2>&1
}

mailer_publish_result() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    publish exchange=mailer.events routing_key=email-delivery.changed payload='{}' > /dev/null 2>&1
}

mailer_read_billing_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    get queue=billing.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

notification_publish_to_platform() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username notification --password "${NOTIFICATION_RABBITMQ_PASSWORD:?}" \
    publish exchange=platform.events routing_key=tenant.changed payload='{}' > /dev/null 2>&1
}

notification_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username notification --password "${NOTIFICATION_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

billing_read_notification_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username billing --password "${BILLING_RABBITMQ_PASSWORD:?}" \
    get queue=notification.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

billing_read_mailroom_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username billing --password "${BILLING_RABBITMQ_PASSWORD:?}" \
    get queue=mailroom.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

# The positive control: notification is the one service user with a cross-service publish grant, and a script that only
# proved denials would keep passing if the grant vanished.
notification_publish_email_command() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username notification --password "${NOTIFICATION_RABBITMQ_PASSWORD:?}" \
    publish exchange=mailer.commands routing_key=email.send payload='{}' > /dev/null 2>&1
}

# Mailroom may publish only to mailer.commands and read only its own queues. In particular it may not publish to
# mailer.events: a consumer of mailer's results that could also write them would make its own ledger.
mailroom_publish_to_platform() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailroom --password "${MAILROOM_RABBITMQ_PASSWORD:?}" \
    publish exchange=platform.events routing_key=tenant.changed payload='{}' > /dev/null 2>&1
}

mailroom_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailroom --password "${MAILROOM_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

mailroom_publish_to_mailer_events() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailroom --password "${MAILROOM_RABBITMQ_PASSWORD:?}" \
    publish exchange=mailer.events routing_key=email-delivery.changed payload='{}' > /dev/null 2>&1
}

mailroom_read_billing_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailroom --password "${MAILROOM_RABBITMQ_PASSWORD:?}" \
    get queue=billing.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

mailroom_publish_email_command() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailroom --password "${MAILROOM_RABBITMQ_PASSWORD:?}" \
    publish exchange=mailer.commands routing_key=email.send payload='{}' > /dev/null 2>&1
}

require_refused "the billing user publishing to platform.events" billing_publish_to_platform
require_refused "the platform user publishing to billing.events" platform_publish_to_billing
require_refused "the mailer user publishing to billing.events" mailer_publish_to_billing
require_refused "the mailer user publishing to platform.events" mailer_publish_to_platform
require_refused "the mailer user publishing to its own command exchange" mailer_publish_to_its_command_exchange
require_refused "the mailer user reading billing.platform-events" mailer_read_billing_queue
if ! mailer_publish_result; then
  fail "the mailer user cannot publish email-delivery.changed to mailer.events; its result grant is missing"
fi
echo "allowed: the mailer user publishing email-delivery.changed to mailer.events"
require_refused "the notification user publishing to platform.events" notification_publish_to_platform
require_refused "the notification user publishing to billing.events" notification_publish_to_billing
require_refused "the billing user reading notification.platform-events" billing_read_notification_queue
if ! notification_publish_email_command; then
  fail "the notification user cannot publish email.send to mailer.commands; the one cross-service grant is missing"
fi
echo "allowed: the notification user publishing email.send to mailer.commands"
require_refused "the mailroom user publishing to platform.events" mailroom_publish_to_platform
require_refused "the mailroom user publishing to billing.events" mailroom_publish_to_billing
require_refused "the mailroom user publishing to mailer.events" mailroom_publish_to_mailer_events
require_refused "the mailroom user reading billing.platform-events" mailroom_read_billing_queue
require_refused "the billing user reading mailroom.platform-events" billing_read_mailroom_queue
if ! mailroom_publish_email_command; then
  fail "the mailroom user cannot publish email.send to mailer.commands; its only publish grant is missing"
fi

# Survey publishes its three facts to survey.events and to nothing else. Its write grant covers only its own exchange
# and its own queue, so publishing to platform.events or billing.events must be refused.
survey_publish_to_platform() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username survey --password "${SURVEY_RABBITMQ_PASSWORD:?}" \
    publish exchange=platform.events routing_key=tenant.changed payload='{}' > /dev/null 2>&1
}

survey_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username survey --password "${SURVEY_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

survey_read_billing_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username survey --password "${SURVEY_RABBITMQ_PASSWORD:?}" \
    get queue=billing.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

survey_publish_own_event() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username survey --password "${SURVEY_RABBITMQ_PASSWORD:?}" \
    publish exchange=survey.events routing_key=survey-response.submitted payload='{}' > /dev/null 2>&1
}

require_refused "the survey user publishing to platform.events" survey_publish_to_platform
require_refused "the survey user publishing to billing.events" survey_publish_to_billing
require_refused "the survey user reading billing.platform-events" survey_read_billing_queue
if ! survey_publish_own_event; then
  fail "the survey user cannot publish survey-response.submitted to survey.events; its own grant is missing"
fi
echo "allowed: the survey user publishing survey-response.submitted to survey.events"
echo "allowed: the mailroom user publishing email.send to mailer.commands"

# Booking owns no exchange: its ten facts leave as signed webhooks. Its write grant covers its own queue, its DLQ and
# suite.dlx, so publishing to platform.events or billing.events must be refused, and it may read only its own queue.
booking_publish_to_platform() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username booking --password "${BOOKING_RABBITMQ_PASSWORD:?}" \
    publish exchange=platform.events routing_key=tenant.changed payload='{}' > /dev/null 2>&1
}

booking_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username booking --password "${BOOKING_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

booking_read_billing_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username booking --password "${BOOKING_RABBITMQ_PASSWORD:?}" \
    get queue=billing.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

require_refused "the booking user publishing to platform.events" booking_publish_to_platform
require_refused "the booking user publishing to billing.events" booking_publish_to_billing
require_refused "the booking user reading billing.platform-events" booking_read_billing_queue

# ------------------------------ row-level security ------------------------------
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

# ------------------------------ a control ------------------------------
step "a service may still reach its own database"
# A control that proves the refusals above are about scope, not about a broken client.
if ! psql_as billing_app "${BILLING_DB_PASSWORD:?}" billing; then
  fail "billing_app cannot reach its own database; the isolation checks are not meaningful"
fi
echo "allowed: billing_app connecting to billing"

if ! psql_as mailer_app "${MAILER_DB_PASSWORD:?}" mailer; then
  fail "mailer_app cannot reach its own database; the isolation checks are not meaningful"
fi
echo "allowed: mailer_app connecting to mailer"

if ! psql_as notification_app "${NOTIFICATION_DB_PASSWORD:?}" notification; then
  fail "notification_app cannot reach its own database; the isolation checks are not meaningful"
fi
echo "allowed: notification_app connecting to notification"

if ! psql_as mailroom_app "${MAILROOM_DB_PASSWORD:?}" mailroom; then
  fail "mailroom_app cannot reach its own database; the isolation checks are not meaningful"
fi
echo "allowed: mailroom_app connecting to mailroom"

if ! psql_as booking_app "${BOOKING_DB_PASSWORD:?}" booking; then
  fail "booking_app cannot reach its own database; the isolation checks are not meaningful"
fi
echo "allowed: booking_app connecting to booking"

echo "PASS: no service can reach another service's database or exchange"
