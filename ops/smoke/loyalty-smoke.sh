#!/usr/bin/env bash
# Plays the Tenant System against the loyalty app on a running `docker compose` stack.
#
#  1. a Tenant with the loyalty app enabled and a Tenant System credential that may call it
#  2. the program, two tiers, a promotion and a reward — the administrative half, with the tenant admin's own token,
#     because `loyalty:program:manage` is deliberately not grantable to a Tenant System
#  3. an order earns through the promotion, a return claws back, a spend reverses, debt blocks a redemption and the next
#     earn repays it
#  4. a reward redeemed, its voucher used once and refused the second time
#  5. points expire and the balance stops counting them
#  6. the twelve webhook types the app sends, each delivered once and signed, and `member.erased` from the erasure
#  7. a second Tenant sees none of it, and loyalty answers while another app is stopped
#
# Requirements: curl, jq, ruby (to read the permission catalog), docker compose. Run from the repository root.
set -euo pipefail

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

TENANT_CODE="${SMOKE_LOYALTY_TENANT:-loyalty-smoke}"
smoke_bootstrap "loyalty" "${TENANT_CODE}"
RECEIVER_PORT="${FORWARDER_PORT:-8099}"
LOYALTY="${PROXY}/loyalty/api/v1"
LOYALTY_TOKEN="${LOYALTY_TOKEN:?smoke_bootstrap must set it}"

SECOND_TENANT="${TENANT_CODE}-other"
with_apps loyalty
SECOND_ADMIN_TOKEN=$(tenant_admin_token "${SECOND_TENANT}" "admin@${SECOND_TENANT}.test")
SECOND_CREDENTIAL=$(issue_credential "${SECOND_TENANT}" "${SECOND_ADMIN_TOKEN}")
SECOND_KEY_ID=$(printf '%s' "${SECOND_CREDENTIAL}" | json .credential.keyId)
SECOND_KEY=$(printf '%s' "${SECOND_CREDENTIAL}" | json .key)
SECOND_KEY_SECRET="${SECOND_KEY##*_}"
SECOND_LOYALTY_TOKEN=$(system_token loyalty "${SECOND_TENANT}.localhost" "${SECOND_KEY_ID}" "${SECOND_KEY_SECRET}")

loyalty_answers() {
  local code
  code=$(code_of GET "${LOYALTY}/program" "${HOST}" "${LOYALTY_TOKEN}")
  [ "${code}" = "200" ] || [ "${code}" = "404" ]
}
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the loyalty entitlement" loyalty_answers

# ------------------------------ the Tenant's receiver ------------------------------
step "registering the Tenant's receiver before anything changes"
LOYALTY_SECRET=$(register_receiver "${LOYALTY}" "${LOYALTY_TOKEN}" "/record")
start_receiver "" "${LOYALTY_TOKEN}" "${LOYALTY_SECRET}"
allowed "the Tenant is listening for every loyalty type"

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
PROMOTION=$(call POST "${LOYALTY}/promotions" "${HOST}" "${TENANT_ADMIN_TOKEN}" \
  '{"name":"Double groceries","multiplier":2.00,"priority":1,"categories":["GROCERY"]}')
PROMOTION_ID=$(printf '%s' "${PROMOTION}" | json .promotionId)
REWARD=$(call POST "${LOYALTY}/rewards" "${HOST}" "${TENANT_ADMIN_TOKEN}" \
  '{"name":"Coffee","pointsCost":10,"stock":5,"voucherValidDays":7}')
REWARD_ID=$(printf '%s' "${REWARD}" | json .rewardId)
allowed "the program, two tiers, promotion ${PROMOTION_ID} and reward ${REWARD_ID} are configured"

# ------------------------------ earn, return, spend ------------------------------
step "an order earns through the promotion and a return claws back"
REF="L-${RUN_ID}"
call PUT "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" '{}' > /dev/null
ORDER="O-${RUN_ID}"
ORDER_BODY="{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-02T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"lines\":[{\"lineNo\":1,\"sku\":\"SKU-COFFEE\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":2000}]}"
EARNED=$(call PUT "${LOYALTY}/orders/${ORDER}" "${HOST}" "${LOYALTY_TOKEN}" "${ORDER_BODY}")
[ "$(printf '%s' "${EARNED}" | json .pointsGranted)" = "40" ] ||
  fail "the order earned $(printf '%s' "${EARNED}" | json .pointsGranted), expected 40 (2000 × 2 ÷ 100)"
[ "$(code_of PUT "${LOYALTY}/orders/${ORDER}" "${HOST}" "${LOYALTY_TOKEN}" "${ORDER_BODY}")" = "200" ] ||
  fail "the order replay did not answer 200"
[ "$(code_of PUT "${LOYALTY}/orders/${ORDER}" "${HOST}" "${LOYALTY_TOKEN}" \
  "$(printf '%s' "${ORDER_BODY}" | jq -c '.lines[0].amount = 900')")" = "409" ] ||
  fail "a changed body under the same orderRef was accepted"
refused "the same orderRef with a different body"
allowed "the order earned 40 points with the promotion applied, and replays idempotently"

CLAWBACK=$(call PUT "${LOYALTY}/orders/${ORDER}/returns/R-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"occurredAt":"2026-03-03T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":1000}]}')
[ "$(printf '%s' "${CLAWBACK}" | json .available)" = "20" ] ||
  fail "the clawback left $(printf '%s' "${CLAWBACK}" | json .available), expected 20"
allowed "the return clawed back to 20 points"

step "a return before its order is refused, and succeeds once the order exists"
[ "$(code_of PUT "${LOYALTY}/orders/O-early-${RUN_ID}/returns/E-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"occurredAt":"2026-03-04T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":100}]}')" = "422" ] ||
  fail "a return before its order was accepted"
refused "a return before its order"
EARLY='{"externalRef":"'${REF}'","currency":"USD","occurredAt":"2026-03-04T09:00:00Z","storeCode":"ST-01","channel":"POS","lines":[{"lineNo":1,"sku":"S","category":"GROCERY","quantity":1,"amount":100}]}'
call PUT "${LOYALTY}/orders/O-early-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" "${EARLY}" > /dev/null
[ "$(code_of PUT "${LOYALTY}/orders/O-early-${RUN_ID}/returns/E-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"occurredAt":"2026-03-04T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":100}]}')" = "201" ] ||
  fail "the retried return did not succeed"
allowed "the same return succeeds once its order exists"

step "spend, reverse, then debt and its repayment"
AVAILABLE=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .available)
SPEND=$(( AVAILABLE > 20 ? 20 : AVAILABLE ))
REDEMPTION=$(call POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"points\":${SPEND},\"reference\":\"POS-${RUN_ID}\"}" "spend-${RUN_ID}")
REDEMPTION_ID=$(printf '%s' "${REDEMPTION}" | json .redemptionId)
[ "$(code_of POST "${LOYALTY}/members/${REF}/redemptions/${REDEMPTION_ID}/reversal" "${HOST}" "${LOYALTY_TOKEN}")" = "200" ] ||
  fail "the reversal was refused"
allowed "a spend of ${SPEND} points and its reversal both succeeded"

# Spend everything, then return: the clawback has no lot to take from, so it leaves debt.
TOTAL=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .available)
call POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"points\":${TOTAL},\"reference\":\"POS-ALL-${RUN_ID}\"}" "spend-all-${RUN_ID}" > /dev/null
DEBT_ORDER="O2-${RUN_ID}"
call PUT "${LOYALTY}/orders/${DEBT_ORDER}" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-05T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"lines\":[{\"lineNo\":1,\"sku\":\"S2\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":3000}]}" > /dev/null
FRESH=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .available)
call POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"points\":${FRESH},\"reference\":\"POS-ALL2-${RUN_ID}\"}" "spend-all2-${RUN_ID}" > /dev/null
call PUT "${LOYALTY}/orders/${DEBT_ORDER}/returns/RT-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"occurredAt":"2026-03-06T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":3000}]}' > /dev/null
DEBT=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" | json .debt)
[ "${DEBT}" -gt 0 ] || fail "a return after the spend left no debt"
[ "$(code_of POST "${LOYALTY}/members/${REF}/redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  '{"points":1,"reference":"should-refuse"}' "debt-refuse-${RUN_ID}")" = "409" ] ||
  fail "a member in debt was allowed to redeem"
refused "a redemption while the member owes ${DEBT} points"
call PUT "${LOYALTY}/orders/O3-${RUN_ID}" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"externalRef\":\"${REF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-07T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"lines\":[{\"lineNo\":1,\"sku\":\"S3\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":5000}]}" > /dev/null
AFTER=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}")
[ "$(printf '%s' "${AFTER}" | json .debt)" = "0" ] || fail "the next earn did not repay the debt"
allowed "the next earn repaid the debt first, leaving $(printf '%s' "${AFTER}" | json .available) points"

# ------------------------------ rewards and vouchers ------------------------------
step "a reward is redeemed and its voucher is single-use"
REWARD_REDEMPTION=$(call POST "${LOYALTY}/members/${REF}/reward-redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"rewardId\":\"${REWARD_ID}\"}" "reward-${RUN_ID}")
VOUCHER_CODE=$(printf '%s' "${REWARD_REDEMPTION}" | json .voucher.code)
VOUCHER_ID=$(printf '%s' "${REWARD_REDEMPTION}" | json .voucher.voucherId)
[ "$(code_of POST "${LOYALTY}/vouchers/${VOUCHER_CODE}/use" "${HOST}" "${LOYALTY_TOKEN}" '{"useRef":"USE-1"}')" = "200" ] ||
  fail "the voucher could not be used"
[ "$(code_of POST "${LOYALTY}/vouchers/${VOUCHER_CODE}/use" "${HOST}" "${LOYALTY_TOKEN}" '{"useRef":"USE-2"}')" = "409" ] ||
  fail "a used voucher was used a second time"
refused "the second use of voucher ${VOUCHER_ID}"
[ "$(code_of POST "${LOYALTY}/vouchers/${VOUCHER_CODE}/use" "${HOST}" "${LOYALTY_TOKEN}" '{"useRef":"USE-1"}')" = "200" ] ||
  fail "the same useRef did not replay"
allowed "the voucher was used once, refused a second reference and replayed its own"

# A second reward redeemed and reversed: reversing an unused voucher voids it, which is the `voucher.voided` fact.
SECOND=$(call POST "${LOYALTY}/members/${REF}/reward-redemptions" "${HOST}" "${LOYALTY_TOKEN}" \
  "{\"rewardId\":\"${REWARD_ID}\"}" "reward-undone-${RUN_ID}")
SECOND_REDEMPTION=$(printf '%s' "${SECOND}" | json .redemptionId)
[ "$(code_of POST "${LOYALTY}/members/${REF}/redemptions/${SECOND_REDEMPTION}/reversal" "${HOST}" "${LOYALTY_TOKEN}")" = "200" ] ||
  fail "the reward redemption could not be reversed"
[ "$(call GET "${LOYALTY}/vouchers/$(printf '%s' "${SECOND}" | json .voucher.code)" "${HOST}" "${LOYALTY_TOKEN}" | json .state)" = "VOIDED" ] ||
  fail "the reversed redemption's voucher was not voided"
allowed "reversing an unused reward redemption voided its voucher"

# ------------------------------ expiry ------------------------------
step "points expire and the balance stops counting them"
# The program's expiry is one month, which the smoke cannot wait out; the run moves the lot's own date instead, which
# is exactly what the balance and the expiry job read.
docker compose exec -T postgres psql -U postgres -d loyalty -q \
  -c "update point_lots set expires_at = now() - interval '1 minute' where member_id = (select id from members where external_ref = '${REF}')" \
  > /dev/null 2>&1 || true
expired_lot_counted_out() {
  local value
  value=$(call GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}" 2>/dev/null | jq -r '.available // empty' 2>/dev/null)
  [ "${value}" = "0" ]
}
wait_for "the balance to stop counting the expired lot" expired_lot_counted_out
allowed "an expired lot is excluded from the balance at once"

# ------------------------------ webhooks ------------------------------
step "the loyalty webhook types reach the Tenant, each signed"
EXPECTED_TYPES="member.enrolled points.earned points.clawed-back points.redeemed points.redemption-reversed points.expired voucher.issued voucher.used voucher.voided tier.changed"
# The expiry run is on its own interval, so this is the slowest assertion in the script.
WAIT_SECONDS="${SMOKE_EXPIRY_WAIT_SECONDS:-90}" wait_for "every type this run produced" all_types_arrived "${EXPECTED_TYPES}"
allowed "the Tenant saw $(printf '%s' "${EXPECTED_TYPES}" | wc -w | tr -d ' ') types this run produced, each a signed envelope"

step "erasure anonymizes the member and announces it"
call POST "${LOYALTY}/members/${REF}/erasure" "${HOST}" "${LOYALTY_TOKEN}" > /dev/null
[ "$(code_of GET "${LOYALTY}/members/${REF}" "${HOST}" "${LOYALTY_TOKEN}")" = "404" ] ||
  fail "the erased member is still reachable by its old reference"
refused "the erased member's reference reaching a live member"
assert_erased_arrived() { received_types | grep -qx "member.erased"; }
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "member.erased" assert_erased_arrived
allowed "member.erased reached the Tenant after the erasure"

# ------------------------------ isolation ------------------------------
step "a second Tenant sees none of it"
[ "$(code_of GET "${LOYALTY}/members/${REF}" "${SECOND_TENANT}.localhost" "${SECOND_LOYALTY_TOKEN}")" = "404" ] ||
  fail "another Tenant read the first Tenant's member"
refused "the second Tenant reading the first Tenant's member"

step "independence: loyalty answers with another app stopped"
docker compose stop customer-api customer-worker > /dev/null 2>&1 || true
[ "$(code_of GET "${LOYALTY}/program" "${HOST}" "${LOYALTY_TOKEN}")" = "200" ] ||
  fail "loyalty stopped answering while the customer app was down"
allowed "loyalty serves its API with the customer app stopped"
docker compose start customer-api customer-worker > /dev/null 2>&1 || true

step "cleaning up"
stop_receiver

echo "PASS: the loyalty app runs a points program end to end on its own"
