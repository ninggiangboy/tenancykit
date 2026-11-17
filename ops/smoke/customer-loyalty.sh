#!/usr/bin/env bash
# Plays the Tenant System against a running `docker compose` stack, on both ways a Tenant may connect the customer app
# and loyalty. It is the integration proof: neither app calls the other, so everything here is the Tenant's own work.
#
#  1. a Tenant with both apps enabled and one Tenant System credential that may call both
#  2. dual-write: one customer and one order sent to both apps; the same points from each; a return clawed back in both
#  3. forwarding: the Tenant's forwarder registered as the customer app's webhook endpoint; an order recorded there
#     reaches loyalty through `order.recorded` and earns the same points as the dual-write route; `customer.erased`
#     anonymizes the member
#  4. a second Tenant sees none of it
#  5. independence: with either app stopped, the other one behaves as before
#
# Requirements: curl, jq, ruby (to read the permission catalogs), docker compose. Run from the repository root.
set -euo pipefail

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

TENANT_CODE="${SMOKE_TENANT:-customer-loyalty-smoke}"
smoke_bootstrap "customer loyalty" "${TENANT_CODE}"
RECEIVER_PORT="${FORWARDER_PORT:-8099}"
CUSTOMER="${PROXY}/customer/api/v1"
LOYALTY="${PROXY}/loyalty/api/v1"

SECOND_TENANT="${TENANT_CODE}-other"
SECOND_ADMIN_TOKEN=$(tenant_admin_token "${SECOND_TENANT}" "admin@${SECOND_TENANT}.test")
SECOND_CREDENTIAL=$(issue_credential "${SECOND_TENANT}" "${SECOND_ADMIN_TOKEN}")
SECOND_KEY_ID=$(printf '%s' "${SECOND_CREDENTIAL}" | json .credential.keyId)
SECOND_KEY=$(printf '%s' "${SECOND_CREDENTIAL}" | json .key)
SECOND_KEY_SECRET="${SECOND_KEY##*_}"
SECOND_CUSTOMER_TOKEN=$(system_token customer "${SECOND_TENANT}.localhost" "${SECOND_KEY_ID}" "${SECOND_KEY_SECRET}")
SECOND_LOYALTY_TOKEN=$(system_token loyalty "${SECOND_TENANT}.localhost" "${SECOND_KEY_ID}" "${SECOND_KEY_SECRET}")

customer_answers() { [ "$(code_of GET "${CUSTOMER}/customers" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ]; }
loyalty_answers() {
  local code
  code=$(code_of GET "${LOYALTY}/program" "${HOST}" "${LOYALTY_TOKEN}")
  [ "${code}" = "200" ] || [ "${code}" = "404" ]
}
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the customer entitlement" customer_answers
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the loyalty entitlement" loyalty_answers

# ------------------------------ the program ------------------------------
step "configuring the program, two tiers, one promotion and one reward"
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
call POST "${LOYALTY}/promotions" "${HOST}" "${TENANT_ADMIN_TOKEN}" \
  '{"name":"Double groceries","multiplier":2.00,"priority":1,"categories":["GROCERY"]}' > /dev/null
REWARD=$(call POST "${LOYALTY}/rewards" "${HOST}" "${TENANT_ADMIN_TOKEN}" \
  '{"name":"Coffee","pointsCost":10,"stock":5,"voucherValidDays":7}')
REWARD_ID=$(printf '%s' "${REWARD}" | json .rewardId)
allowed "the program, two tiers, a promotion and reward ${REWARD_ID} are configured"

# ------------------------------ the Tenant's forwarder ------------------------------
step "registering the Tenant's own receiver and forwarder"
CUSTOMER_SECRET=$(register_receiver "${CUSTOMER}" "${CUSTOMER_TOKEN}" "/hook")
LOYALTY_SECRET=$(register_receiver "${LOYALTY}" "${LOYALTY_TOKEN}" "/record")
# The forwarder verifies the customer app's signature and maps its three events onto loyalty; the same receiver records
# loyalty's webhooks so this run can assert what the Tenant saw.
start_receiver "${CUSTOMER_SECRET}" "${LOYALTY_TOKEN}" "${LOYALTY_SECRET}"
allowed "the forwarder is subscribed to the customer app and the receiver to loyalty"

# ------------------------------ dual-write ------------------------------
step "dual-write: one customer and one order sent to both apps"
REF="M-${RUN_ID}"
call PUT "${CUSTOMER}/customers/${REF}" "${HOST}" "${CUSTOMER_TOKEN}" '{"displayName":"Ada Smoke"}' > /dev/null
call PUT "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" '{}' > /dev/null

ORDER="O-${RUN_ID}"
ORDER_BODY=$(cat <<EOF
{"externalRef":"${REF}","currency":"USD","occurredAt":"2026-03-02T10:00:00Z","storeCode":"ST-01","channel":"POS",
 "total":2000,"lines":[{"lineNo":1,"sku":"SKU-COFFEE","category":"GROCERY","quantity":1,"amount":2000}]}
EOF
)
# loyalty reads no total; it is stripped before the same order is sent there.
LOYALTY_ORDER_BODY=$(printf '%s' "${ORDER_BODY}" | jq -c 'del(.total)')
CUSTOMER_STATUS=$(code_of PUT "${CUSTOMER}/orders/${ORDER}" "${HOST}" "${CUSTOMER_TOKEN}" "${ORDER_BODY}")
[ "${CUSTOMER_STATUS}" = "201" ] || fail "the customer app did not record the order (${CUSTOMER_STATUS})"
DUAL=$(call PUT "${LOYALTY}/orders/${ORDER}" "${HOST}" "${LOYALTY_TOKEN}" "${LOYALTY_ORDER_BODY}")
DUAL_EARNED=$(printf '%s' "${DUAL}" | json .pointsGranted)
[ "${DUAL_EARNED}" = "40" ] || fail "the dual-write order earned ${DUAL_EARNED}, expected 40 (2000 × 2 ÷ 100)"
allowed "the dual-write order earned ${DUAL_EARNED} points with the promotion applied"

[ "$(code_of PUT "${CUSTOMER}/orders/${ORDER}/returns/R-${RUN_ID}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"occurredAt":"2026-03-03T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":1000}]}')" = "201" ] ||
  fail "the customer app did not record the return"
DUAL_RETURN=$(call PUT "${LOYALTY}/orders/${ORDER}/returns/R-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"occurredAt":"2026-03-03T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":1000}]}')
[ "$(printf '%s' "${DUAL_RETURN}" | json .available)" = "20" ] ||
  fail "the dual-write return left $(printf '%s' "${DUAL_RETURN}" | json .available), expected 20"
allowed "the dual-write return clawed back to 20 points in both apps"

# ------------------------------ forwarding ------------------------------
step "forwarding: an order recorded in the customer app only reaches loyalty through order.recorded"
FREF="F-${RUN_ID}"
FORWARD_ORDER="OF-${RUN_ID}"
FORWARD_BODY=$(cat <<EOF
{"externalRef":"${FREF}","currency":"USD","occurredAt":"2026-03-07T10:00:00Z","storeCode":"ST-01","channel":"POS",
 "total":2000,"createCustomer":true,
 "lines":[{"lineNo":1,"sku":"SKU-COFFEE","category":"GROCERY","quantity":1,"amount":2000,"earnEligible":true}]}
EOF
)
[ "$(code_of PUT "${CUSTOMER}/orders/${FORWARD_ORDER}" "${HOST}" "${CUSTOMER_TOKEN}" "${FORWARD_BODY}")" = "201" ] ||
  fail "the customer app did not record the forwarded order"
forwarded_points() {
  local value
  value=$(call GET "${LOYALTY}/members/${FREF}" "${HOST}" "${LOYALTY_TOKEN}" 2>/dev/null | jq -r '.available // empty' 2>/dev/null)
  [ "${value}" = "40" ]
}
wait_for "the forwarded order to earn 40 points in loyalty" forwarded_points
allowed "the forwarded order earned the same 40 points the dual-write order did"

step "forwarding: a return and an erasure in the customer app reach loyalty"
[ "$(code_of PUT "${CUSTOMER}/orders/${FORWARD_ORDER}/returns/RF-${RUN_ID}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"occurredAt":"2026-03-08T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":1000}]}')" = "201" ] ||
  fail "the customer app did not record the forwarded return"
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
[ "$(code_of POST "${CUSTOMER}/customers/${FREF}/erasure" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ] ||
  fail "the customer app refused the erasure"
erased() {
  [ "$(code_of GET "${LOYALTY}/members/${FREF}" "${HOST}" "${LOYALTY_TOKEN}")" = "404" ]
}
wait_for "the forwarded erasure to anonymize the member" erased
refused "the erased member's reference reaching loyalty"
# The forwarder announced the erasure, so the Tenant learned about it on the loyalty side too.
assert_erased_arrived() { received_types | grep -qx "member.erased"; }
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "member.erased" assert_erased_arrived
allowed "customer.erased reached loyalty through the Tenant's forwarder and anonymized the member"

# ------------------------------ isolation ------------------------------
step "a second Tenant sees none of it"
[ "$(code_of GET "${CUSTOMER}/customers/${REF}" "${SECOND_TENANT}.localhost" "${SECOND_CUSTOMER_TOKEN}")" = "404" ] ||
  fail "another Tenant read the first Tenant's customer"
refused "the second Tenant reading the first Tenant's customer"
[ "$(code_of GET "${LOYALTY}/members/${REF}" "${SECOND_TENANT}.localhost" "${SECOND_LOYALTY_TOKEN}")" = "404" ] ||
  fail "another Tenant read the first Tenant's member"
refused "the second Tenant reading the first Tenant's member"

# ------------------------------ independence ------------------------------
step "independence: with the customer app stopped, loyalty answers"
docker compose stop customer-api customer-worker > /dev/null
[ "$(code_of GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}")" = "200" ] ||
  fail "loyalty stopped answering with the customer app down"
allowed "loyalty answers with the customer app stopped"
docker compose start customer-api customer-worker > /dev/null
customer_back() { [ "$(code_of GET "${CUSTOMER}/customers" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ]; }
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the customer app to answer again" customer_back

step "independence: with loyalty stopped, the customer app answers"
docker compose stop loyalty-api loyalty-worker > /dev/null
[ "$(code_of GET "${CUSTOMER}/customers" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ] ||
  fail "the customer app stopped answering with loyalty down"
IND_BODY="{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-09T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"total\":100,\"lines\":[{\"lineNo\":1,\"sku\":\"SKU-9\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":100}]}"
IND_STATUS=$(code_of PUT "${CUSTOMER}/orders/IND-${RUN_ID}" "${HOST}" "${CUSTOMER_TOKEN}" "${IND_BODY}")
[ "${IND_STATUS}" = "201" ] || fail "the customer app could not record an order with loyalty down (${IND_STATUS})"
allowed "the customer app records an order with loyalty stopped"
docker compose start loyalty-api loyalty-worker > /dev/null

step "cleaning up"
stop_receiver

echo "PASS: the Tenant connects the customer app and loyalty itself, by dual write and by forwarding"
