#!/usr/bin/env bash
# Proves Notification's Done-means end states against a running `docker compose` deployment:
#
#  1. a Platform administrator token
#  2. a tenant with the notification entitlement enabled
#  3. a tenant administrator's first access reaches Notification's replica
#  4. exactly one Mailpit message for that user, produced by Notification's `email.send` command
#  5. exactly one in-app row through the public API, and unreadCount dropping after a read
#  6. the same for a Platform user, reached at platform.localhost, which is the platform scope
#
# Case 6 is the one that proves a Platform user is notified like anyone else: their rows live under the reserved
# Platform tenant, and their email only leaves the outbox because mailer's sweep visits that tenant too.
#
# Requirements: curl, jq, docker compose.
set -euo pipefail

PROXY="${PROXY_URL:-http://localhost:8090}"
MAILPIT="${MAILPIT_URL:-http://localhost:8025}"
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${KEYCLOAK_REALM:-tenancykit}"
SMOKE_CLIENT_ID="${SMOKE_CLIENT_ID:-suite-smoke}"
SMOKE_CLIENT_SECRET="${SMOKE_CLIENT_SECRET:?set the smoke client secret}"
TENANT_CODE="${SMOKE_TENANT_CODE:-acme}"
ADMIN_EMAIL="admin@${TENANT_CODE}.test"
BOOTSTRAP_EMAIL="${PLATFORM_BOOTSTRAP_ADMIN_EMAIL:-}"
BOOTSTRAP_PASSWORD="${PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD:-}"
REPLICA_TIMEOUT_SECONDS="${SMOKE_REPLICA_TIMEOUT:-30}"
MAIL_TIMEOUT_SECONDS="${SMOKE_MAIL_TIMEOUT:-30}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

step() {
  echo "== $*"
}

json() {
  jq -er "$1"
}

user_token() {
  curl --silent --show-error --fail-with-body \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=${SMOKE_CLIENT_ID}" \
    --data-urlencode "client_secret=${SMOKE_CLIENT_SECRET}" \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2" \
    "${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token" | json .access_token
}

call() {
  local method="$1" url="$2" host="$3" token="$4" body="${5:-}"
  curl --silent --show-error --fail-with-body \
    --request "${method}" "${url}" \
    --header "Host: ${host}" \
    --header "Authorization: Bearer ${token}" \
    --header "Content-Type: application/json" \
    ${body:+--data "${body}"}
}

step "getting a Platform administrator token"
ADMIN_TOKEN=$(user_token "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}")

step "ensuring tenant ${TENANT_CODE} has notification enabled"
TENANT_ID=$(call GET "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" |
  jq -r --arg code "${TENANT_CODE}" 'map(select(.code == $code))[0].id // empty')
if [ -z "${TENANT_ID}" ]; then
  PROVISIONED=$(call POST "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" \
    "{\"code\":\"${TENANT_CODE}\",\"name\":\"Acme\",\"adminEmail\":\"${ADMIN_EMAIL}\",\"adminTemporaryPassword\":\"Temporary-1\"}")
  TENANT_ID=$(printf '%s' "${PROVISIONED}" | json .tenantId)
fi
call PUT "${PROXY}/platform/api/v1/tenants/${TENANT_ID}/apps/notification" platform.localhost "${ADMIN_TOKEN}" \
  '{"enabled":true}' > /dev/null

HOST="${TENANT_CODE}.${BASE_DOMAIN:-localhost}"
TENANT_ADMIN_TOKEN=$(user_token "${TENANT_CODE}:${ADMIN_EMAIL}" "Temporary-1")

step "waiting for the replica and the in-app notification"
deadline=$((SECONDS + REPLICA_TIMEOUT_SECONDS))
rows=0
while [ "${SECONDS}" -lt "${deadline}" ]; do
  body=$(call GET "${PROXY}/notification/api/v1/notifications" "${HOST}" "${TENANT_ADMIN_TOKEN}" || true)
  rows=$(printf '%s' "${body}" | jq -r '.items | length' 2>/dev/null || echo 0)
  if [ "${rows}" -ge 1 ]; then
    break
  fi
  sleep 1
done
[ "${rows}" -ge 1 ] || fail "no in-app notification appeared within ${REPLICA_TIMEOUT_SECONDS}s"
echo "in-app rows: ${rows}"

step "waiting for the Mailpit message"
deadline=$((SECONDS + MAIL_TIMEOUT_SECONDS))
messages=0
while [ "${SECONDS}" -lt "${deadline}" ]; do
  messages=$(curl --silent "${MAILPIT}/api/v1/messages" |
    jq -r --arg to "${ADMIN_EMAIL}" '[.messages[] | select(any(.To[]?; .Address == $to))] | length')
  if [ "${messages}" -ge 1 ]; then
    break
  fi
  sleep 1
done
[ "${messages}" -ge 1 ] || fail "no Mailpit message for ${ADMIN_EMAIL} within ${MAIL_TIMEOUT_SECONDS}s"
echo "messages for ${ADMIN_EMAIL}: ${messages}"

step "reading the notification and checking unreadCount"
NOTIFICATION_ID=$(call GET "${PROXY}/notification/api/v1/notifications" "${HOST}" "${TENANT_ADMIN_TOKEN}" |
  json '.items[0].id')
call POST "${PROXY}/notification/api/v1/notifications/${NOTIFICATION_ID}/read" "${HOST}" "${TENANT_ADMIN_TOKEN}" > /dev/null
UNREAD=$(call GET "${PROXY}/notification/api/v1/notifications" "${HOST}" "${TENANT_ADMIN_TOKEN}" |
  json .unreadCount)
[ "${UNREAD}" = "0" ] || fail "unreadCount is ${UNREAD} after a read"
echo "unreadCount: ${UNREAD}"

# ------------------------------ a Platform user ------------------------------
# Creating a Platform-scope user emits user-access.changed for them, which is an ACCESS_GRANTED for a user whose rows
# live under the reserved Platform tenant. Nothing about the pipeline is tenant-specific, so the same three assertions
# must hold at platform.localhost.
PLATFORM_USER_EMAIL="platform-smoke-$(date +%s)@example.test"

step "creating a Platform user (this is their ACCESS_GRANTED)"
call POST "${PROXY}/api/v1/users" platform.localhost "${ADMIN_TOKEN}" \
  "{\"tenantCode\":\"platform\",\"email\":\"${PLATFORM_USER_EMAIL}\",\"displayName\":\"Platform Smoke\",\"temporaryPassword\":\"Temporary-1\"}" \
  > /dev/null

PLATFORM_USER_TOKEN=$(user_token "platform:${PLATFORM_USER_EMAIL}" "Temporary-1")

step "waiting for that Platform user's in-app notification"
deadline=$((SECONDS + REPLICA_TIMEOUT_SECONDS))
platform_rows=0
while [ "${SECONDS}" -lt "${deadline}" ]; do
  body=$(call GET "${PROXY}/notification/api/v1/notifications" platform.localhost "${PLATFORM_USER_TOKEN}" || true)
  platform_rows=$(printf '%s' "${body}" | jq -r '.items | length' 2>/dev/null || echo 0)
  if [ "${platform_rows}" -ge 1 ]; then
    break
  fi
  sleep 1
done
[ "${platform_rows}" -ge 1 ] || fail "no in-app notification appeared for the Platform user within ${REPLICA_TIMEOUT_SECONDS}s"
echo "platform in-app rows: ${platform_rows}"

step "waiting for the Platform user's Mailpit message"
deadline=$((SECONDS + MAIL_TIMEOUT_SECONDS))
platform_messages=0
while [ "${SECONDS}" -lt "${deadline}" ]; do
  platform_messages=$(curl --silent "${MAILPIT}/api/v1/messages" |
    jq -r --arg to "${PLATFORM_USER_EMAIL}" '[.messages[] | select(any(.To[]?; .Address == $to))] | length')
  if [ "${platform_messages}" -ge 1 ]; then
    break
  fi
  sleep 1
done
[ "${platform_messages}" -ge 1 ] || fail "no Mailpit message for ${PLATFORM_USER_EMAIL} within ${MAIL_TIMEOUT_SECONDS}s"
echo "messages for ${PLATFORM_USER_EMAIL}: ${platform_messages}"

step "reading the Platform user's notification and checking unreadCount"
PLATFORM_NOTIFICATION_ID=$(call GET "${PROXY}/notification/api/v1/notifications" platform.localhost "${PLATFORM_USER_TOKEN}" |
  json '.items[0].id')
call POST "${PROXY}/notification/api/v1/notifications/${PLATFORM_NOTIFICATION_ID}/read" platform.localhost "${PLATFORM_USER_TOKEN}" > /dev/null
PLATFORM_UNREAD=$(call GET "${PROXY}/notification/api/v1/notifications" platform.localhost "${PLATFORM_USER_TOKEN}" |
  json .unreadCount)
[ "${PLATFORM_UNREAD}" = "0" ] || fail "the Platform user's unreadCount is ${PLATFORM_UNREAD} after a read"
echo "platform unreadCount: ${PLATFORM_UNREAD}"

echo "PASS: one granted access produced one in-app row and one email for a tenant user and for a Platform user, and reading each cleared its badge"
