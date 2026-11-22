#!/usr/bin/env bash
# Proves the suite's Done-means end states against a running `docker compose` deployment.
#
#  1. a Platform administrator token
#  2. a tenant created with an admin, its billing app enabled
#  3. Billing's replica answering for the tenant host within the lag budget
#  4. a tenant-system credential and the suite token it issues, used to call Billing
#  5. a charge and the invoice issued for it
#  6. permission revocation at Platform reaching Billing within the window
#
# Users are created with a temporary password, which Keycloak refuses from a direct grant until it is changed. The
# harness step below clears that for the two accounts this script drives, through Keycloak's admin API, exactly as
# an operator would set up a test account. Product behaviour is untouched.
#
# Every step exits non-zero with a message on the first failure. Requirements: curl, jq, openssl, docker compose.
set -euo pipefail

PROXY="${PROXY_URL:-http://localhost:8090}"
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${KEYCLOAK_REALM:-tenancykit}"
KC_ADMIN_CLIENT_ID="${PLATFORM_KEYCLOAK_ADMIN_CLIENT_ID:-platform-admin}"
KC_ADMIN_CLIENT_SECRET="${PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET:?set the platform-admin client secret}"
SMOKE_CLIENT_ID="${SMOKE_CLIENT_ID:-suite-smoke}"
SMOKE_CLIENT_SECRET="${SMOKE_CLIENT_SECRET:?set the smoke client secret}"
TENANT_CODE="${SMOKE_TENANT_CODE:-acme}"
ADMIN_EMAIL="admin@${TENANT_CODE}.test"
BOOTSTRAP_EMAIL="${PLATFORM_BOOTSTRAP_ADMIN_EMAIL:-}"
BOOTSTRAP_PASSWORD="${PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD:-}"
REPLICA_TIMEOUT_SECONDS="${SMOKE_REPLICA_TIMEOUT:-15}"
REVOCATION_TIMEOUT_SECONDS="${SMOKE_REVOCATION_TIMEOUT:-5}"
# Every run uses its own ids, so a repeat run never collides with one that already finished.
RUN_ID="$(date +%s)-$$"

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

# ------------------------------ Keycloak admin harness ------------------------------
# An administrative token from the platform-admin service account (it carries kc-admin-api).
kc_admin_token() {
  curl --silent --show-error --fail-with-body \
    --data-urlencode "grant_type=client_credentials" \
    --data-urlencode "client_id=${KC_ADMIN_CLIENT_ID}" \
    --data-urlencode "client_secret=${KC_ADMIN_CLIENT_SECRET}" \
    "${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token" | json .access_token
}

# Sets a permanent password and clears the update-password action, so a direct grant works. It changes only the two
# accounts this script uses; the suite's own flows still create users with a temporary password.
make_loginable() {
  local username="$1" password="$2"
  local user_id
  user_id=$(curl --silent --show-error --fail-with-body \
    --header "Authorization: Bearer ${KC_ADMIN}" \
    "${KEYCLOAK}/admin/realms/${REALM}/users?exact=true&username=$(printf '%s' "${username}" | jq -sRr @uri)" |
    jq -er '.[0].id')
  curl --silent --show-error --fail-with-body --output /dev/null \
    --request PUT "${KEYCLOAK}/admin/realms/${REALM}/users/${user_id}" \
    --header "Authorization: Bearer ${KC_ADMIN}" \
    --header "Content-Type: application/json" \
    --data '{"requiredActions":[],"enabled":true}'
  curl --silent --show-error --fail-with-body --output /dev/null \
    --request PUT "${KEYCLOAK}/admin/realms/${REALM}/users/${user_id}/reset-password" \
    --header "Authorization: Bearer ${KC_ADMIN}" \
    --header "Content-Type: application/json" \
    --data "{\"type\":\"password\",\"value\":\"${password}\",\"temporary\":false}"
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

# The proxy keeps the Host header, so each call names the host whose tenant scope it wants. Platform is reached
# under /platform on any host; Billing is reached at the root of a tenant host.
call() {
  local method="$1" url="$2" host="$3" token="$4" body="${5:-}" key="${6:-}"
  curl --silent --show-error --fail-with-body \
    --request "${method}" "${url}" \
    --header "Host: ${host}" \
    --header "Authorization: Bearer ${token}" \
    --header "Content-Type: application/json" \
    ${key:+--header "Idempotency-Key: ${key}"} \
    ${body:+--data "${body}"}
}

status() {
  curl --silent --output /dev/null --write-out '%{http_code}' \
    --header "Host: $2" \
    --header "Authorization: Bearer $3" \
    "$1"
}

# ------------------------------ 0. the harness ------------------------------
step "making the two driven accounts loginable"
KC_ADMIN=$(kc_admin_token)
[ -n "${BOOTSTRAP_EMAIL}" ] || fail "PLATFORM_BOOTSTRAP_ADMIN_EMAIL must name the Platform administrator"
# Platform creates the first administrator under the platform pseudo-tenant, so its Keycloak username is
# `platform:<email>`.
make_loginable "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}"

# ------------------------------ 1. a Platform administrator token ------------------------------
step "getting a Platform administrator token"
ADMIN_TOKEN=$(user_token "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}")

# ------------------------------ 2. create the tenant and enable billing ------------------------------
step "creating tenant ${TENANT_CODE} with an admin and enabling billing"
# A repeat run finds the tenant already there; the tenant list tells its id, so the script is re-runnable.
TENANT_ID=$(call GET "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" |
  jq -r --arg code "${TENANT_CODE}" '(.data // .) | map(select(.code == $code))[0].id // empty')
if [ -z "${TENANT_ID}" ]; then
  PROVISIONED=$(call POST "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" \
    "{\"code\":\"${TENANT_CODE}\",\"name\":\"Acme\",\"adminEmail\":\"${ADMIN_EMAIL}\",\"adminTemporaryPassword\":\"Temporary-1\"}")
  TENANT_ID=$(printf '%s' "${PROVISIONED}" | json .tenantId)
fi
call PUT "${PROXY}/platform/api/v1/tenants/${TENANT_ID}/apps/billing" platform.localhost "${ADMIN_TOKEN}" \
  '{"enabled":true}' > /dev/null

# Platform creates the tenant admin as Keycloak username `<tenant-code>:<email>`; make that one loginable too.
make_loginable "${TENANT_CODE}:${ADMIN_EMAIL}" "Temporary-1"
TENANT_ADMIN_TOKEN=$(user_token "${TENANT_CODE}:${ADMIN_EMAIL}" "Temporary-1")

# ------------------------------ 3. the replica appears in Billing ------------------------------
step "waiting up to ${REPLICA_TIMEOUT_SECONDS}s for Billing's replica of ${TENANT_CODE}"
START=$(date +%s)
while :; do
  if [ "$(status "${PROXY}/api/v1/customers" "${TENANT_CODE}.localhost" "${TENANT_ADMIN_TOKEN}")" = "200" ]; then
    break
  fi
  if [ $(( $(date +%s) - START )) -ge "${REPLICA_TIMEOUT_SECONDS}" ]; then
    fail "Billing did not accept the tenant admin within ${REPLICA_TIMEOUT_SECONDS}s (replica lag)"
  fi
  sleep 1
done
echo "replica caught up in $(( $(date +%s) - START ))s"

# ------------------------------ 4. a tenant-system token calls Billing ------------------------------
step "creating a tenant system and credential, then calling Billing with its suite token"
# Re-running reuses the Tenant System this script creates, so a name collision never fails the second run.
SYSTEM_ID=$(call GET "${PROXY}/platform/api/v1/tenant-systems" "${TENANT_CODE}.localhost" "${TENANT_ADMIN_TOKEN}" |
  jq -r '(.data // .) | map(select(.name == "smoke"))[0].id // empty')
if [ -z "${SYSTEM_ID}" ]; then
  SYSTEM_ID=$(call POST "${PROXY}/platform/api/v1/tenant-systems" "${TENANT_CODE}.localhost" "${TENANT_ADMIN_TOKEN}" \
    '{"name":"smoke"}' | json .id)
fi
CREDENTIAL=$(call POST "${PROXY}/platform/api/v1/tenant-systems/${SYSTEM_ID}/credentials" \
  "${TENANT_CODE}.localhost" "${TENANT_ADMIN_TOKEN}" \
  '{"label":"smoke","scopes":["billing:customer:read","billing:customer:write","billing:charge:create","billing:invoice:create","billing:invoice:read",
  "billing:wallet:manage","billing:wallet:read","billing:webhook:manage","billing:webhook:read",
  "billing:coupon:read","billing:coupon:manage","billing:coupon:redeem"]}')
KEY=$(printf '%s' "${CREDENTIAL}" | json .key)
KEY_ID=$(printf '%s' "${CREDENTIAL}" | json .credential.keyId)
KEY_SECRET="${KEY##*_}"

SYSTEM_TOKEN=$(curl --silent --show-error --fail-with-body \
  --user "${KEY_ID}:${KEY_SECRET}" \
  --data "grant_type=client_credentials&audience=billing" \
  --header "Host: ${TENANT_CODE}.localhost" \
  "${PROXY}/platform/oauth/token" | json .access_token)

# Billing authenticates the Tenant System from its replica, which the tenant-system.changed event fills a moment
# after creation. A 401 means the system is not there yet; wait for the replica to accept the token before the first
# call, so a run right after creation is not a race.
START=$(date +%s)
while [ "$(status "${PROXY}/api/v1/customers" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}")" = "401" ]; do
  if [ $(( $(date +%s) - START )) -ge "${REPLICA_TIMEOUT_SECONDS}" ]; then
    fail "Billing did not accept the tenant system within ${REPLICA_TIMEOUT_SECONDS}s (replica lag)"
  fi
  sleep 1
done

CUSTOMER_ID=$(call POST "${PROXY}/api/v1/customers" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"externalUserId\":\"smoke-user-${RUN_ID}\",\"displayName\":\"Smoke\",\"email\":\"smoke@acme.test\"}" "smoke-customer-${RUN_ID}" | json .id)

# ------------------------------ 5. charge and invoice ------------------------------
step "charging the wallet and issuing an invoice"
WALLET_ID=$(call POST "${PROXY}/api/v1/wallets" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"customerId\":\"${CUSTOMER_ID}\",\"currency\":\"USD\",\"mode\":\"POSTPAID\",\"creditLimit\":1000000}" | json .id)
CHARGE_ID=$(call POST "${PROXY}/api/v1/charges" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"walletId\":\"${WALLET_ID}\",\"amount\":1200,\"currency\":\"USD\",\"reference\":\"smoke-${RUN_ID}\"}" "smoke-charge-${RUN_ID}" | json .id)
call POST "${PROXY}/api/v1/invoices" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"chargeId\":\"${CHARGE_ID}\"}" > /dev/null

# ------------------------------ 5b. a coupon, redeemed and applied ------------------------------
step "redeeming a coupon and charging a discounted amount"
COUPON_ID=$(call POST "${PROXY}/api/v1/coupons" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"name\":\"Smoke 20\",\"currency\":\"USD\",\"benefit\":{\"type\":\"PERCENT_OFF\",\"basisPoints\":2000}, \
    \"limits\":{\"redeemBy\":\"2099-01-01T00:00:00Z\"},\"stackable\":true,\"code\":\"SMOKE20-${RUN_ID}\"}" | json .coupon.id)
call POST "${PROXY}/api/v1/wallets/${WALLET_ID}/coupon-redemptions" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"code\":\"SMOKE20-${RUN_ID}\"}" "smoke-redeem-${RUN_ID}" > /dev/null
# 20% off 1,000 is 800; the ledger takes the net, and the charge keeps its gross.
COUPON_CHARGE=$(call POST "${PROXY}/api/v1/charges" "${TENANT_CODE}.localhost" "${SYSTEM_TOKEN}" \
  "{\"walletId\":\"${WALLET_ID}\",\"amount\":1000,\"currency\":\"USD\",\"reference\":\"smoke-coupon-${RUN_ID}\"}" "smoke-coupon-charge-${RUN_ID}")
if [ "$(printf '%s' "${COUPON_CHARGE}" | json .amount.amount)" != "800" ] || \
   [ "$(printf '%s' "${COUPON_CHARGE}" | json .grossAmount.amount)" != "1000" ]; then
  fail "a coupon did not discount the charge as expected: ${COUPON_CHARGE}"
fi

# ------------------------------ 6. revocation latency ------------------------------
step "revoking the tenant admin's role at Platform and asserting Billing refuses within ${REVOCATION_TIMEOUT_SECONDS}s"
ROLE_ID=$(call GET "${PROXY}/platform/api/v1/roles?tenantId=${TENANT_ID}" platform.localhost "${ADMIN_TOKEN}" |
  jq -er '(.data // .) | map(select(.name == "Tenant Administrator"))[0].id')
call PUT "${PROXY}/platform/api/v1/roles/${ROLE_ID}/permissions" platform.localhost "${ADMIN_TOKEN}" \
  '{"permissions":[]}' > /dev/null

DEADLINE=$(( $(date +%s) + REVOCATION_TIMEOUT_SECONDS ))
while :; do
  CODE=$(status "${PROXY}/api/v1/customers" "${TENANT_CODE}.localhost" "${TENANT_ADMIN_TOKEN}")
  if [ "${CODE}" = "403" ]; then
    break
  fi
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    fail "Billing still answered ${CODE} after the admin's permissions were revoked"
  fi
  sleep 1
done

echo "PASS: the suite's Done-means checks all hold"
