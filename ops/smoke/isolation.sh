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

# Mailer consumes commands and publishes nothing, so both of these must fail: it may not write to another service's
# exchange, and it may not read another service's queue — a message there carries that service's work.
mailer_publish_to_billing() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    publish exchange=billing.events routing_key=customer.created payload='{}' > /dev/null 2>&1
}

mailer_read_billing_queue() {
  docker compose exec -T rabbitmq \
    rabbitmqadmin --host rabbitmq --username mailer --password "${MAILER_RABBITMQ_PASSWORD:?}" \
    get queue=billing.platform-events count=1 ackmode=reject_requeue_true > /dev/null 2>&1
}

require_refused "the billing user publishing to platform.events" billing_publish_to_platform
require_refused "the platform user publishing to billing.events" platform_publish_to_billing
require_refused "the mailer user publishing to billing.events" mailer_publish_to_billing
require_refused "the mailer user reading billing.platform-events" mailer_read_billing_queue

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

echo "PASS: no service can reach another service's database or exchange"
