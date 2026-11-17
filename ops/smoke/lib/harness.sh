#!/usr/bin/env bash
# Shared harness for the suite's app smoke scripts. Sourced, never executed: it owns the parts every app smoke needs
# (the Platform administrator, one Tenant with its entitlement, a Tenant System credential and its suite token, and the
# HTTP helpers) so each app's smoke reads as the scenarios it proves and nothing else.
#
# A sourcing script must set SMOKE_APPS to the app codes it enables (for example "customer" or "customer loyalty")
# before calling `smoke_bootstrap`.

set -euo pipefail

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

PROXY="${PROXY_URL:-http://localhost:8090}"
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${KEYCLOAK_REALM:-billing}"
BOOTSTRAP_EMAIL="${PLATFORM_BOOTSTRAP_ADMIN_EMAIL:?set the Platform bootstrap admin}"
BOOTSTRAP_PASSWORD="${PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD:?set the Platform bootstrap password}"
SMOKE_CLIENT_SECRET="${SMOKE_CLIENT_SECRET:?set the smoke client secret}"
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-20}"
# A restarted app reloads its replica and must answer readiness first, which takes longer than a poll.
RESTART_SECONDS="${SMOKE_RESTART_SECONDS:-90}"
RUN_ID="$(date +%s)-$$"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

step() {
  echo "== $*"
}

allowed() {
  echo "allowed: $*"
}

refused() {
  echo "refused: $*"
}

# ------------------------------ HTTP helpers ------------------------------

call() {
  local method="$1" url="$2" host="$3" token="$4" body="${5:-}" key="${6:-}"
  curl --silent --show-error \
    --request "${method}" "${url}" \
    --header "Host: ${host}" \
    --header "Authorization: Bearer ${token}" \
    --header "Content-Type: application/json" \
    ${key:+--header "Idempotency-Key: ${key}"} \
    ${body:+--data "${body}"}
}

code_of() {
  local method="$1" url="$2" host="$3" token="$4" body="${5:-}" key="${6:-}"
  curl --silent --output /dev/null --write-out '%{http_code}' \
    --request "${method}" "${url}" \
    --header "Host: ${host}" \
    --header "Authorization: Bearer ${token}" \
    --header "Content-Type: application/json" \
    ${key:+--header "Idempotency-Key: ${key}"} \
    ${body:+--data "${body}"}
}

json() {
  jq -er "$1"
}

# Waits for a condition a function answers, with a deadline rather than a fixed sleep.
wait_for() {
  local description="$1" deadline=$(( $(date +%s) + WAIT_SECONDS ))
  shift
  # A predicate that shells out may exit non-zero for a reason the script's `set -e` must not act on: the point of a
  # poll is to retry, so each attempt runs in a subshell whose failure means "not yet".
  while ! ( "$@" ) 2>/dev/null; do
    if [ "$(date +%s)" -ge "${deadline}" ]; then
      fail "${description} did not happen within ${WAIT_SECONDS}s"
    fi
    sleep 1
  done
}

# ------------------------------ the harness ------------------------------

kc_token() {
  local username="$1" password="$2"
  curl --silent --show-error --fail-with-body \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=suite-smoke" \
    --data-urlencode "client_secret=${SMOKE_CLIENT_SECRET}" \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}" \
    "${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token" | json .access_token
}

# A user Platform created with a temporary password cannot sign in until it is changed; this is the harness step the
# other smokes perform, and it touches only the accounts a smoke drives.
make_loginable() {
  local username="$1" password="$2"
  local master user_id
  master=$(curl --silent --show-error --fail-with-body \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}" \
    --data-urlencode "password=${KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD:?set the Keycloak admin password}" \
    "${KEYCLOAK}/realms/master/protocol/openid-connect/token" | json .access_token)
  user_id=$(curl --silent --show-error --fail-with-body \
    --header "Authorization: Bearer ${master}" \
    "${KEYCLOAK}/admin/realms/${REALM}/users?exact=true&username=$(printf '%s' "${username}" | jq -sRr @uri)" |
    jq -er '.[0].id')
  curl --silent --show-error --fail-with-body --output /dev/null \
    --request PUT "${KEYCLOAK}/admin/realms/${REALM}/users/${user_id}" \
    --header "Authorization: Bearer ${master}" --header "Content-Type: application/json" \
    --data '{"requiredActions":[],"enabled":true}'
  curl --silent --show-error --fail-with-body --output /dev/null \
    --request PUT "${KEYCLOAK}/admin/realms/${REALM}/users/${user_id}/reset-password" \
    --header "Authorization: Bearer ${master}" --header "Content-Type: application/json" \
    --data "{\"type\":\"password\",\"value\":\"${password}\",\"temporary\":false}"
}

# The app codes a run enables, one per argument. A smoke that needs two apps passes both.
with_apps() {
  SMOKE_APPS="$*"
}

# Creates the Tenant when it is missing, enables exactly the apps this run needs, makes its admin loginable and answers
# its administrator token on stdout.
tenant_admin_token() {
  local code="$1" email="$2" id
  id=$(call GET "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" |
    jq -r --arg code "${code}" '.data | map(select(.code == $code))[0].id // empty')
  if [ -z "${id}" ]; then
    id=$(call POST "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" \
      "{\"code\":\"${code}\",\"name\":\"${code}\",\"adminEmail\":\"${email}\",\"adminTemporaryPassword\":\"Temporary-1\"}" |
      json .tenantId)
  fi
  local app
  for app in ${SMOKE_APPS}; do
    call PUT "${PROXY}/platform/api/v1/tenants/${id}/apps/${app}" platform.localhost "${ADMIN_TOKEN}" \
      '{"enabled":true}' > /dev/null
  done
  make_loginable "${code}:${email}" "Temporary-1"
  kc_token "${code}:${email}" "Temporary-1"
}

# Every code the enabled apps grant a Tenant System, as a JSON array. The catalogs are the source of truth.
tenant_system_scopes() {
  ruby -ryaml -e '
    codes = ARGV.flat_map { |app|
      YAML.load_file("contracts/permissions/#{app}.yaml")
          .select { |p| p["grantable_to_tenant_system"] }
          .map { |p| p["code"] }
    }
    require "json"; puts JSON.generate(codes.uniq)
  ' ${SMOKE_APPS}
}

# A Tenant System credential for the tenant, carrying every code this run's apps grant one. Re-running reuses the Tenant
# System and issues a fresh credential, so a second run never collides.
issue_credential() {
  local code="$1" admin_token="$2" system_id
  system_id=$(call GET "${PROXY}/platform/api/v1/tenant-systems" "${code}.localhost" "${admin_token}" |
    jq -r '.data | map(select(.name == "smoke"))[0].id // empty')
  if [ -z "${system_id}" ]; then
    system_id=$(call POST "${PROXY}/platform/api/v1/tenant-systems" "${code}.localhost" "${admin_token}" \
      '{"name":"smoke"}' | json .id)
  fi
  call POST "${PROXY}/platform/api/v1/tenant-systems/${system_id}/credentials" \
    "${code}.localhost" "${admin_token}" \
    "{\"label\":\"smoke-${RUN_ID}\",\"scopes\":$(tenant_system_scopes)}"
}

# A suite token for one audience, minted from a credential the tenant holds.
system_token() {
  local audience="$1" host="$2" id="$3" secret="$4"
  curl --silent --show-error --fail-with-body \
    --user "${id}:${secret}" \
    --data "grant_type=client_credentials&audience=${audience}" \
    --header "Host: ${host}" \
    "${PROXY}/platform/oauth/token" | json .access_token
}

# Registers the Tenant's receiver and answers its signing secret. A previous run's endpoint would keep delivering with
# its own secret, so this run starts from none; a delete is a conditional update carrying the version the read answered.
register_receiver() {
  local base="$1" token="$2" path="$3" id etag created
  for id in $(curl --silent "${base}/webhook-endpoints" --header "Host: ${HOST}" \
    --header "Authorization: Bearer ${token}" | jq -r '.data[]?.id // empty'); do
    etag=$(curl --silent --head "${base}/webhook-endpoints/${id}" --header "Host: ${HOST}" \
      --header "Authorization: Bearer ${token}" |
      awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
    [ -n "${etag}" ] || continue
    curl --silent --output /dev/null --request DELETE "${base}/webhook-endpoints/${id}" \
      --header "Host: ${HOST}" --header "Authorization: Bearer ${token}" \
      --header "If-Match: ${etag}" || true
  done
  created=$(call POST "${base}/webhook-endpoints" "${HOST}" "${token}" \
    "{\"url\":\"http://tenant-forwarder:8080${path}\",\"description\":\"smoke receiver\",\"eventTypes\":[\"*\"]}")
  printf '%s' "${created}" | json .signingSecret
}

# Starts the Tenant's own receiver/forwarder, which is test scaffolding and not a suite service.
start_receiver() {
  local crm_secret="${1:-}" loyalty_token="${2:-}" loyalty_secret="${3:-}"
  # The receiver is rebuilt every run: a cached image would keep an older handler and an older signature check.
  stop_receiver
  FORWARDER_CRM_SECRET="${crm_secret}" FORWARDER_LOYALTY_TOKEN="${loyalty_token}" \
    FORWARDER_LOYALTY_SECRET="${loyalty_secret}" FORWARDER_LOYALTY_HOST="${HOST}" \
    docker compose -f docker-compose.yml -f ops/smoke/tenant-forwarder/docker-compose.smoke.yml --profile smoke \
    up -d --build --wait tenant-forwarder > /dev/null
}

stop_receiver() {
  docker compose -f docker-compose.yml -f ops/smoke/tenant-forwarder/docker-compose.smoke.yml --profile smoke \
    rm -sf tenant-forwarder > /dev/null 2>&1 || true
}

# What the Tenant's receiver recorded, newest last.
received_types() {
  curl --silent "http://localhost:${FORWARDER_PORT:-8099}/received" | jq -r 'map(.type) | unique | .[]' 2>/dev/null
}

# Every type in the space-separated first argument must have arrived at the Tenant's receiver.
all_types_arrived() {
  local type
  for type in $1; do
    received_types | grep -qx "${type}" || return 1
  done
  return 0
}

# The whole harness in one call: the Platform administrator, one Tenant with this run's apps, its admin token, its
# Tenant System credential and a suite token per app. Sets ADMIN_TOKEN, TENANT_ID, TENANT_ADMIN_TOKEN, HOST and one
# <APP>_TOKEN per app (upper-cased).
smoke_bootstrap() {
  local apps="$1" code="$2" app token_var credential key key_id key_secret
  with_apps ${apps}
  step "getting a Platform administrator token"
  ADMIN_TOKEN=$(kc_token "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}")

  step "creating tenant ${code} with ${apps} enabled"
  TENANT_ID=$(call GET "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" |
    jq -r --arg code "${code}" '.data | map(select(.code == $code))[0].id // empty')
  TENANT_ADMIN_TOKEN=$(tenant_admin_token "${code}" "admin@${code}.test")
  if [ -z "${TENANT_ID}" ]; then
    TENANT_ID=$(call GET "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" |
      jq -r --arg code "${code}" '.data | map(select(.code == $code))[0].id // empty')
  fi
  HOST="${code}.localhost"
  # A fresh Tenant is served once Platform's own request scope resolves its host; issuing a credential before that
  # answers 500 from the audit row rather than the tenant list.
  tenant_ready() { [ "$(code_of GET "${PROXY}/platform/api/v1/tenant-systems" "${HOST}" "${TENANT_ADMIN_TOKEN}")" = "200" ]; }
  WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the tenant ${code} to answer" tenant_ready

  step "issuing a Tenant System credential and its suite tokens"
  credential=$(issue_credential "${code}" "${TENANT_ADMIN_TOKEN}")
  key=$(printf '%s' "${credential}" | json .key)
  key_id=$(printf '%s' "${credential}" | json .credential.keyId)
  key_secret="${key##*_}"
  for app in ${apps}; do
    token_var="$(printf '%s' "${app}" | tr '[:lower:]' '[:upper:]')_TOKEN"
    printf -v "${token_var}" '%s' "$(system_token "${app}" "${HOST}" "${key_id}" "${key_secret}")"
  done
}
