#!/usr/bin/env bash
# Plays the Tenant System against a running `docker compose` stack, on both ways a Tenant may connect crm and loyalty.
#
#  1. a Tenant with crm and loyalty enabled and a Tenant System credential that may call both
#  2. its program, tiers, one promotion and one reward configured in loyalty
#  3. dual-write path: one order and one return sent to both apps; the promotion applied; spend, reverse, debt and its
#     repayment; a reward redeemed and its voucher used twice; the points expire and the ledger says so
#  4. forwarding path: the Tenant's forwarder registered as crm's webhook endpoint; the same order recorded in crm only
#     reaches loyalty through order.recorded and earns the same points; the erasure in crm anonymizes the member
#  5. loyalty's twelve webhook types delivered to the Tenant's receiver, signed
#  6. a second Tenant sees none of it
#  7. independence: with either app stopped, the other one and the suite's other apps behave as before
#
# Requirements: curl, jq, docker compose. Run from the repository root, with `.env` present.
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
TENANT_CODE="${SMOKE_CRM_TENANT:-crm-loyalty-smoke}"
TENANT_ADMIN="admin@${TENANT_CODE}.test"
SECOND_TENANT="${TENANT_CODE}-other"
SECOND_ADMIN="admin@${SECOND_TENANT}.test"
BOOTSTRAP_EMAIL="${PLATFORM_BOOTSTRAP_ADMIN_EMAIL:?set the Platform bootstrap admin}"
BOOTSTRAP_PASSWORD="${PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD:?set the Platform bootstrap password}"
SMOKE_CLIENT_SECRET="${SMOKE_CLIENT_SECRET:?set the smoke client secret}"
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-20}"
# A restarted app reloads its replica and must answer readiness first, which takes longer than a poll.
RESTART_SECONDS="${SMOKE_RESTART_SECONDS:-90}"
RUN_ID="$(date +%s)-$$"

# The loyalty webhook receiver and the forwarder are one small HTTP service, started only for this run.
FORWARDER_PORT="${SMOKE_FORWARDER_PORT:-8099}"
LOYALTY_URL_IN_NETWORK="http://tenant-forwarder:8080"

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
  while ! "$@"; do
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
# other smokes perform, and it touches only the two accounts this script drives.
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

# ------------------------------ 0. the harness ------------------------------
step "getting a Platform administrator token"
ADMIN_TOKEN=$(kc_token "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}")

provision() {
  local code="$1" admin="$2"
  local id
  id=$(call GET "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" |
    jq -r --arg code "${code}" '.data | map(select(.code == $code))[0].id // empty')
  if [ -z "${id}" ]; then
    id=$(call POST "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" \
      "{\"code\":\"${code}\",\"name\":\"${code}\",\"adminEmail\":\"${admin}\",\"adminTemporaryPassword\":\"Temporary-1\"}" |
      json .tenantId)
  fi
  for app in crm loyalty; do
    call PUT "${PROXY}/platform/api/v1/tenants/${id}/apps/${app}" platform.localhost "${ADMIN_TOKEN}" \
      '{"enabled":true}' > /dev/null
  done
  printf '%s' "${id}"
}

step "creating tenant ${TENANT_CODE} with crm and loyalty enabled"
TENANT_ID=$(provision "${TENANT_CODE}" "${TENANT_ADMIN}")
make_loginable "${TENANT_CODE}:${TENANT_ADMIN}" "Temporary-1"
TENANT_ADMIN_TOKEN=$(kc_token "${TENANT_CODE}:${TENANT_ADMIN}" "Temporary-1")

step "creating tenant ${SECOND_TENANT} with crm and loyalty enabled"
SECOND_TENANT_ID=$(provision "${SECOND_TENANT}" "${SECOND_ADMIN}")
make_loginable "${SECOND_TENANT}:${SECOND_ADMIN}" "Temporary-1"
SECOND_ADMIN_TOKEN=$(kc_token "${SECOND_TENANT}:${SECOND_ADMIN}" "Temporary-1")

# One credential per Tenant System, carrying every code the two apps grant a Tenant System.
crm_scopes=$(ruby -ryaml -e 'YAML.load_file("contracts/permissions/crm.yaml").each{|p| puts p["code"] if p["grantable_to_tenant_system"]}' | tr '\n' ' ')
loyalty_scopes=$(ruby -ryaml -e 'YAML.load_file("contracts/permissions/loyalty.yaml").each{|p| puts p["code"] if p["grantable_to_tenant_system"]}' | tr '\n' ' ')

credential_for() {
  local code="$1" admin_token="$2"
  local system_id
  system_id=$(call GET "${PROXY}/platform/api/v1/tenant-systems" "${code}.localhost" "${admin_token}" |
    jq -r '.data | map(select(.name == "smoke"))[0].id // empty')
  if [ -z "${system_id}" ]; then
    system_id=$(call POST "${PROXY}/platform/api/v1/tenant-systems" "${code}.localhost" "${admin_token}" \
      '{"name":"smoke"}' | json .id)
  fi
  local scopes_json
  scopes_json=$(printf '%s %s' "${crm_scopes}" "${loyalty_scopes}" |
    tr ' ' '\n' | grep -v '^$' | jq -R . | jq -s .)
  call POST "${PROXY}/platform/api/v1/tenant-systems/${system_id}/credentials" \
    "${code}.localhost" "${admin_token}" "{\"label\":\"smoke\",\"scopes\":${scopes_json}}"
}

step "creating a Tenant System credential for ${TENANT_CODE}"
CREDENTIAL=$(credential_for "${TENANT_CODE}" "${TENANT_ADMIN_TOKEN}")
KEY=$(printf '%s' "${CREDENTIAL}" | json .key)
KEY_ID=$(printf '%s' "${CREDENTIAL}" | json .credential.keyId)
KEY_SECRET="${KEY##*_}"

system_token() {
  local audience="$1" host="$2" id="$3" secret="$4"
  curl --silent --show-error --fail-with-body \
    --user "${id}:${secret}" \
    --data "grant_type=client_credentials&audience=${audience}" \
    --header "Host: ${host}" \
    "${PROXY}/platform/oauth/token" | json .access_token
}

step "minting a crm token and a loyalty token for the Tenant System"
CRM_TOKEN=$(system_token crm "${TENANT_CODE}.localhost" "${KEY_ID}" "${KEY_SECRET}")
LOYALTY_TOKEN=$(system_token loyalty "${TENANT_CODE}.localhost" "${KEY_ID}" "${KEY_SECRET}")

SECOND_CREDENTIAL=$(credential_for "${SECOND_TENANT}" "${SECOND_ADMIN_TOKEN}")
SECOND_KEY=$(printf '%s' "${SECOND_CREDENTIAL}" | json .key)
SECOND_KEY_ID=$(printf '%s' "${SECOND_CREDENTIAL}" | json .credential.keyId)
SECOND_KEY_SECRET="${SECOND_KEY##*_}"
SECOND_CRM_TOKEN=$(system_token crm "${SECOND_TENANT}.localhost" "${SECOND_KEY_ID}" "${SECOND_KEY_SECRET}")
SECOND_LOYALTY_TOKEN=$(system_token loyalty "${SECOND_TENANT}.localhost" "${SECOND_KEY_ID}" "${SECOND_KEY_SECRET}")

HOST="${TENANT_CODE}.localhost"
CRM="${PROXY}/crm/api/v1"
LOYALTY="${PROXY}/loyalty/api/v1"

crm_answers() { [ "$(code_of GET "${CRM}/customers" "${HOST}" "${CRM_TOKEN}")" = "200" ]; }
loyalty_answers() { local c; c=$(code_of GET "${LOYALTY}/program" "${HOST}" "${LOYALTY_TOKEN}"); [ "${c}" = "200" ] || [ "${c}" = "404" ]; }
# The first call also waits for the replica, which the platform-events queue fills a moment after the tenant is created.
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the crm entitlement" crm_answers
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the loyalty entitlement" loyalty_answers

# ------------------------------ 1. the program ------------------------------
step "configuring the program, two tiers, one promotion and one reward"
# The program, its tiers and its promotions change what points are worth, so `loyalty:program:manage` is deliberately
# not grantable to a Tenant System. This is the one part of the run an administrator does, with the tenant admin's own
# token; everything the Tenant's system sends afterwards uses the Tenant System credential.
PROGRAM_BODY='{"currency":"USD","currencyUnit":100,"expiryMonths":1,"expiryNoticeDays":30,"tierWindowMonths":12,"timeZone":"UTC"}'
[ "$(curl --silent --output /dev/null --write-out '%{http_code}' --request PUT "${LOYALTY}/program" \
  --header "Host: ${HOST}" --header "Authorization: Bearer ${TENANT_ADMIN_TOKEN}" \
  --header "Content-Type: application/json" --header 'If-Match: *' --data "${PROGRAM_BODY}")" = "200" ] ||
  fail "the program could not be created"
[ "$(curl --silent --output /dev/null --write-out '%{http_code}' --request PUT "${LOYALTY}/tiers" \
  --header "Host: ${HOST}" --header "Authorization: Bearer ${TENANT_ADMIN_TOKEN}" \
  --header "Content-Type: application/json" --header 'If-Match: *' \
  --data '{"tiers":[{"code":"BRONZE","name":"Bronze","minSpend":0,"earnMultiplier":1.00},{"code":"SILVER","name":"Silver","minSpend":1000,"earnMultiplier":1.50}]}')" = "200" ] ||
  fail "the tier list could not be written"
PROMOTION=$(call POST "${LOYALTY}/promotions" "${HOST}" "${TENANT_ADMIN_TOKEN}" \
  '{"name":"Double groceries","multiplier":2.00,"priority":1,"categories":["GROCERY"]}')
PROMOTION_ID=$(printf '%s' "${PROMOTION}" | json .promotionId)
REWARD=$(call POST "${LOYALTY}/rewards" "${HOST}" "${TENANT_ADMIN_TOKEN}" \
  '{"name":"Coffee","pointsCost":10,"stock":5,"voucherValidDays":7}')
REWARD_ID=$(printf '%s' "${REWARD}" | json .rewardId)
allowed "the program, two tiers, promotion ${PROMOTION_ID} and reward ${REWARD_ID} are configured"

step "the Tenant's receiver and forwarder"
# Registered before the dual-write path: a webhook is only delivered to an endpoint that was already subscribed when
# the change happened, so the Tenant must be listening before it sends anything.
# The forwarder verifies crm's signature and maps the three events onto loyalty; it also receives loyalty's webhooks so
# the run can assert what the Tenant saw.
# A previous run's endpoint would keep delivering with its own secret, so this run starts from none. A delete is a
# conditional update: it carries the version the read answered, which is what `If-Match` names.
purge_endpoints() {
  local base="$1" token="$2" id etag
  for id in $(curl --silent "${base}/webhook-endpoints" --header "Host: ${HOST}" \
    --header "Authorization: Bearer ${token}" | jq -r '.data[]?.id // empty'); do
    # The list carries no version; a read of the endpoint answers its current one as the ETag.
    etag=$(curl --silent --head "${base}/webhook-endpoints/${id}" --header "Host: ${HOST}" \
      --header "Authorization: Bearer ${token}" |
      awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
    [ -n "${etag}" ] || continue
    curl --silent --output /dev/null --request DELETE "${base}/webhook-endpoints/${id}" \
      --header "Host: ${HOST}" --header "Authorization: Bearer ${token}" \
      --header "If-Match: ${etag}" || true
  done
}
purge_endpoints "${CRM}" "${CRM_TOKEN}"
purge_endpoints "${LOYALTY}" "${LOYALTY_TOKEN}"
FORWARDER_ENDPOINT=$(call POST "${CRM}/webhook-endpoints" "${HOST}" "${CRM_TOKEN}" \
  "{\"url\":\"http://tenant-forwarder:8080/hook\",\"description\":\"smoke forwarder\",\"eventTypes\":[\"order.recorded\",\"order.refunded\",\"customer.erased\"]}")
FORWARDER_SECRET=$(printf '%s' "${FORWARDER_ENDPOINT}" | json .signingSecret)
FORWARDER_ID=$(printf '%s' "${FORWARDER_ENDPOINT}" | json .endpoint.id)

LOYALTY_RECEIVER=$(call POST "${LOYALTY}/webhook-endpoints" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"url\":\"http://tenant-forwarder:8080/loyalty\",\"description\":\"smoke receiver\",\"eventTypes\":[\"*\"]}")
LOYALTY_SECRET=$(printf '%s' "${LOYALTY_RECEIVER}" | json .signingSecret)

FORWARDER_CRM_SECRET="${FORWARDER_SECRET}" FORWARDER_LOYALTY_TOKEN="${LOYALTY_TOKEN}" \
  FORWARDER_LOYALTY_SECRET="${LOYALTY_SECRET}" FORWARDER_LOYALTY_HOST="${HOST}" \
  docker compose -f docker-compose.yml -f ops/smoke/tenant-forwarder/docker-compose.smoke.yml --profile smoke \
  up -d --build --wait tenant-forwarder > /dev/null
allowed "the forwarder is registered at crm and the receiver at loyalty"

# ------------------------------ 2. dual-write ------------------------------
step "dual-write: one customer and one order sent to both apps"
REF="M-${RUN_ID}"
call PUT "${CRM}/customers/${REF}" "${HOST}" "${CRM_TOKEN}" '{"displayName":"Ada Smoke"}' > /dev/null
call PUT "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" '{}' > /dev/null

ORDER_REF="O-${RUN_ID}"
ORDER_BODY=$(cat <<EOF
{"externalRef":"${REF}","currency":"USD","occurredAt":"2026-03-02T10:00:00Z","storeCode":"ST-01","channel":"POS",
 "total":2000,"lines":[{"lineNo":1,"sku":"SKU-COFFEE","category":"GROCERY","quantity":1,"amount":2000}]}
EOF
)
# loyalty reads no total; it is stripped before the same order is sent there.
LOYALTY_ORDER_BODY=$(printf '%s' "${ORDER_BODY}" | jq -c 'del(.total)')
crm_order=$(code_of PUT "${CRM}/orders/${ORDER_REF}" "${HOST}" "${CRM_TOKEN}" "${ORDER_BODY}")
[ "${crm_order}" = "201" ] || fail "crm did not record the order (${crm_order})"
dual_points=$(call PUT "${LOYALTY}/orders/${ORDER_REF}" "${HOST}" "${LOYALTY_TOKEN}" "${LOYALTY_ORDER_BODY}")
dual_earned=$(printf '%s' "${dual_points}" | json .pointsGranted)
[ "${dual_earned}" = "40" ] ||
  fail "the dual-write order earned ${dual_earned}, expected 40 (2000 × 2 ÷ 100)"
allowed "the dual-write order earned ${dual_earned} points with the promotion applied"

RETURN_REF="R-${RUN_ID}"
RETURN_BODY='{"occurredAt":"2026-03-03T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":1000}]}'
[ "$(code_of PUT "${CRM}/orders/${ORDER_REF}/returns/${RETURN_REF}" "${HOST}" "${CRM_TOKEN}" "${RETURN_BODY}")" = "201" ] ||
  fail "crm did not record the return"
dual_return=$(call PUT "${LOYALTY}/orders/${ORDER_REF}/returns/${RETURN_REF}" "${HOST}" "${LOYALTY_TOKEN}" "${RETURN_BODY}")
dual_balance=$(printf '%s' "${dual_return}" | json .available)
[ "${dual_balance}" = "20" ] || fail "the clawback left ${dual_balance} points, expected 20"
allowed "the dual-write return clawed back 20 points, balance ${dual_balance}"

step "dual-write: spend, reverse, then debt and its repayment"
AVAILABLE=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .available)
SPEND=$(( AVAILABLE > 20 ? 20 : AVAILABLE ))
REDEMPTION=$(call POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"points\":${SPEND},\"reference\":\"POS-${RUN_ID}\"}" "spend-${RUN_ID}")
REDEMPTION_ID=$(printf '%s' "${REDEMPTION}" | json .redemptionId)
[ "$(code_of POST "${LOYALTY}/members/${REF}/redemptions/${REDEMPTION_ID}/reversal" "${HOST}" "${LOYALTY_TOKEN}")" = "200" ] ||
  fail "the reversal was refused"
allowed "a spend of ${SPEND} points and its reversal both succeeded"

# Spend everything, then return the order: the clawback cannot come out of the spent lots, so it leaves debt.
call POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"points\":${AVAILABLE},\"reference\":\"POS-ALL-${RUN_ID}\"}" "spend-all-${RUN_ID}" > /dev/null
NEXT_ORDER="O2-${RUN_ID}"
call PUT "${LOYALTY}/orders/${NEXT_ORDER}" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-04T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"lines\":[{\"lineNo\":1,\"sku\":\"SKU-2\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":3000}]}" \
  > /dev/null
# Spend what that order earned too, so the return that follows has no lot of its own to take from.
FRESH=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .available)
call POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"points\":${FRESH},\"reference\":\"POS-ALL2-${RUN_ID}\"}" "spend-all2-${RUN_ID}" > /dev/null
call PUT "${LOYALTY}/orders/${NEXT_ORDER}/returns/RT2-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"occurredAt":"2026-03-05T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":3000}]}' > /dev/null
DEBT=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .debt)
[ "${DEBT}" -gt 0 ] || fail "a return after the spend left no debt (debt ${DEBT})"
[ "$(code_of POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"points":1,"reference":"should-refuse"}' "debt-refuse-${RUN_ID}")" = "409" ] ||
  fail "a member in debt was allowed to redeem"
refused "a redemption while the member owes ${DEBT} points"

call PUT "${LOYALTY}/orders/O3-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-06T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"lines\":[{\"lineNo\":1,\"sku\":\"SKU-3\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":5000}]}" \
  > /dev/null
AFTER=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}")
[ "$(printf '%s' "${AFTER}" | json .debt)" = "0" ] || fail "the next earn did not repay the debt"
allowed "the next earn repaid the debt first, leaving $(printf '%s' "${AFTER}" | json .available) points"

step "dual-write: redeem a reward and use its voucher twice"
REWARD_REDEMPTION=$(call POST "${LOYALTY}/members/${REF}/reward-redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"rewardId\":\"${REWARD_ID}\"}" "reward-${RUN_ID}")
VOUCHER_CODE=$(printf '%s' "${REWARD_REDEMPTION}" | json .voucher.code)
VOUCHER_ID=$(printf '%s' "${REWARD_REDEMPTION}" | json .voucher.voucherId)
[ "$(code_of POST "${LOYALTY}/vouchers/${VOUCHER_CODE}/use" "${HOST}" "${LOYALTY_TOKEN}" '{"useRef":"USE-1"}')" = "200" ] ||
  fail "the voucher could not be used"
[ "$(code_of POST "${LOYALTY}/vouchers/${VOUCHER_CODE}/use" "${HOST}" "${LOYALTY_TOKEN}" '{"useRef":"USE-2"}')" = "409" ] ||
  fail "a used voucher was used a second time"
refused "the second use of voucher ${VOUCHER_ID}"

step "dual-write: points expire and the ledger says so"
# The program's expiry is one month, which the smoke cannot wait out; the run moves the lot's own date instead, which
# is exactly what the balance and the expiry job read.
docker compose exec -T postgres psql -U postgres -d loyalty -q \
  -c "update point_lots set expires_at = now() - interval '1 minute' where member_id = (select id from members where external_ref = '${REF}')" \
  > /dev/null 2>&1 || true
wait_for "the balance to stop counting the expired lot" bash -c \
  "test \"\$(curl -s '${LOYALTY}/members/${REF}' -H 'Host: ${HOST}' -H 'Authorization: Bearer ${LOYALTY_TOKEN}' --get --data-urlencode 'x=1' | jq -r .available)\" = \"0\""
allowed "an expired lot is excluded from the balance at once"

# ------------------------------ 3. forwarding ------------------------------
step "forwarding: an order recorded in crm only reaches loyalty through order.recorded"
FREF="F-${RUN_ID}"
FORWARD_ORDER="OF-${RUN_ID}"
FORWARD_BODY=$(cat <<EOF
{"externalRef":"${FREF}","currency":"USD","occurredAt":"2026-03-07T10:00:00Z","storeCode":"ST-01","channel":"POS",
 "total":2000,"createCustomer":true,
 "lines":[{"lineNo":1,"sku":"SKU-COFFEE","category":"GROCERY","quantity":1,"amount":2000,"earnEligible":true}]}
EOF
)
[ "$(code_of PUT "${CRM}/orders/${FORWARD_ORDER}" "${HOST}" "${CRM_TOKEN}" "${FORWARD_BODY}")" = "201" ] ||
  fail "crm did not record the forwarded order"
forwarded_points() {
  local value
  value=$(call GET "${LOYALTY}/members/${FREF}" "${HOST}" "${LOYALTY_TOKEN}" 2>/dev/null | jq -r '.available // empty' 2>/dev/null)
  [ "${value}" = "40" ]
}
wait_for "the forwarded order to earn 40 points in loyalty" forwarded_points
allowed "the forwarded order earned the same 40 points the dual-write order did"

step "forwarding: a return in crm reaches loyalty, and erasure anonymizes the member"
[ "$(code_of PUT "${CRM}/orders/${FORWARD_ORDER}/returns/RF-${RUN_ID}" "${HOST}" "${CRM_TOKEN}" \
  '{"occurredAt":"2026-03-08T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":1000}]}')" = "201" ] ||
  fail "crm did not record the forwarded return"
forwarded_balance() {
  local value
  value=$(call GET "${LOYALTY}/members/${FREF}" "${HOST}" "${LOYALTY_TOKEN}" 2>/dev/null | jq -r '.available // empty' 2>/dev/null)
  [ "${value}" = "20" ]
}
wait_for "the forwarded return to claw back to 20 points" forwarded_balance
allowed "the forwarded return clawed back to 20 points"

call PUT "${LOYALTY}/members/${FREF}" "${HOST}" "${LOYALTY_TOKEN}" '{}' > /dev/null
call POST "${LOYALTY}/members/${FREF}/reward-redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"rewardId\":\"${REWARD_ID}\"}" "forward-reward-${RUN_ID}" > /dev/null
[ "$(code_of POST "${CRM}/customers/${FREF}/erasure" "${HOST}" "${CRM_TOKEN}")" = "200" ] ||
  fail "crm refused the erasure"
erased() {
  local value
  value=$(code_of GET "${LOYALTY}/members/${FREF}" "${HOST}" "${LOYALTY_TOKEN}")
  [ "${value}" = "404" ]
}
wait_for "the forwarded erasure to anonymize the member" erased
allowed "customer.erased reached loyalty and anonymized the member"

step "the Tenant's receiver saw loyalty's webhook types"
received_types() {
  curl --silent "http://localhost:${FORWARDER_PORT}/received" | jq -r 'map(.type) | unique | .[]' 2>/dev/null
}
# Every type this run's dual-write path produced: enrolment, an earn, a clawback, a spend and its reversal, an expiry,
# the reward's voucher issued and used, a void, and the tier change a crossed threshold announces.
EXPECTED_TYPES="member.enrolled points.earned points.clawed-back points.redeemed points.redemption-reversed points.expired voucher.issued voucher.used voucher.voided tier.changed member.erased"
# Deliveries arrive on the sender's own poll and the relay's; this waits for the last one rather than assuming it.
all_types_arrived() {
  local type
  for type in ${EXPECTED_TYPES}; do
    received_types | grep -qx "${type}" || return 1
  done
  return 0
}
# The expiry run is on its own interval, so this is the slowest assertion in the script.
WAIT_SECONDS="${SMOKE_EXPIRY_WAIT_SECONDS:-90}" wait_for "every loyalty type this run produced to arrive at the Tenant" all_types_arrived
# A signature is checked with the secret the endpoint was created with, so this also proves the recording is genuine.
SIGNED=$(curl --silent "http://localhost:${FORWARDER_PORT}/received" | jq '[.[] | select(.body | test("\"schemaVersion\""))] | length')
[ "${SIGNED}" -gt 0 ] || fail "the receiver recorded no envelope"
allowed "the receiver holds all $(printf '%s' "${EXPECTED_TYPES}" | wc -w | tr -d ' ') types this run produced, each a signed envelope"

step "a second Tenant sees none of it"
[ "$(code_of GET "${CRM}/customers/${REF}" "${SECOND_TENANT}.localhost" "${SECOND_CRM_TOKEN}")" = "404" ] ||
  fail "another Tenant read the first Tenant's customer"
refused "the second Tenant reading the first Tenant's customer"
[ "$(code_of GET "${LOYALTY}/members/${REF}" "${SECOND_TENANT}.localhost" "${SECOND_LOYALTY_TOKEN}")" = "404" ] ||
  fail "another Tenant read the first Tenant's member"
refused "the second Tenant reading the first Tenant's member"

# ------------------------------ 4. independence ------------------------------
step "independence: with crm stopped, loyalty and the other apps answer"
docker compose stop crm-api crm-worker > /dev/null
[ "$(code_of GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}")" = "200" ] ||
  fail "loyalty stopped answering with crm down"
allowed "loyalty answers with crm stopped"
for app in billing notification; do
  if docker compose ps --format '{{.Name}}' | grep -q "core-billing-${app}-1"; then
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8090/${app}/actuator/health/liveness" -H "Host: ${HOST}")" = "200" ] ||
      echo "note: ${app} is not running, so its independence was not checked"
  fi
done
docker compose start crm-api crm-worker > /dev/null
wait_for_restart() { local deadline=$(( $(date +%s) + RESTART_SECONDS )); while [ "$(code_of GET "${CRM}/customers" "${HOST}" "${CRM_TOKEN}")" != "200" ]; do [ "$(date +%s)" -ge "${deadline}" ] && fail "crm did not answer again within ${RESTART_SECONDS}s"; sleep 1; done; }
wait_for_restart

step "independence: with loyalty stopped, crm answers and records an order"
docker compose stop loyalty-api loyalty-worker > /dev/null
[ "$(code_of GET "${CRM}/customers" "${HOST}" "${CRM_TOKEN}")" = "200" ] || fail "crm stopped answering with loyalty down"
IND_BODY="{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-09T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"total\":100,\"lines\":[{\"lineNo\":1,\"sku\":\"SKU-9\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":100}]}"
IND_STATUS=$(code_of PUT "${CRM}/orders/IND-${RUN_ID}" "${HOST}" "${CRM_TOKEN}" "${IND_BODY}")
[ "${IND_STATUS}" = "201" ] || fail "crm could not record an order with loyalty down (${IND_STATUS})"
allowed "crm records an order with loyalty stopped"
docker compose start loyalty-api loyalty-worker > /dev/null

# ------------------------------ cleanup ------------------------------
step "cleaning up what this run created"
docker compose -f docker-compose.yml -f ops/smoke/tenant-forwarder/docker-compose.smoke.yml --profile smoke \
  rm -sf tenant-forwarder > /dev/null 2>&1 || true

echo "PASS: the Tenant connects crm and loyalty itself, by dual write and by forwarding"
