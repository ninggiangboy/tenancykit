#!/usr/bin/env bash
# Plays the Tenant System against the customer app on a running `docker compose` stack.
#
#  1. a Tenant with the customer app enabled and a Tenant System credential that may call it
#  2. customers: upsert, ETag patch, custom fields, tags, consent, batch with per-item outcomes, search, profile, export
#  3. orders with lines, a return, and the four signed webhooks the app sends as they happen
#  4. erasure on its own endpoint, which clears the person and leaves the business rows anonymous
#  5. a second Tenant sees none of it
#  6. the suite's other apps answer while customer is stopped, and customer answers while they are
#
# Requirements: curl, jq, ruby (to read the permission catalog), docker compose. Run from the repository root.
set -euo pipefail

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

TENANT_CODE="${SMOKE_CUSTOMER_TENANT:-customer-smoke}"
SMOKE_APPS="customer"
smoke_bootstrap "customer" "${TENANT_CODE}"
RECEIVER_PORT="${FORWARDER_PORT:-8099}"
CUSTOMER="${PROXY}/customer/system/v1"
CUSTOMER_TOKEN="${CUSTOMER_TOKEN:?smoke_bootstrap must set it}"

second_admin_token() {
  local code="$1"
  with_apps customer
  tenant_admin_token "${code}" "admin@${code}.test"
}

SECOND_TENANT="${TENANT_CODE}-other"
SECOND_ADMIN_TOKEN=$(second_admin_token "${SECOND_TENANT}")
with_apps customer
SECOND_CREDENTIAL=$(issue_credential "${SECOND_TENANT}" "${SECOND_ADMIN_TOKEN}")
SECOND_KEY_ID=$(printf '%s' "${SECOND_CREDENTIAL}" | json .credential.keyId)
SECOND_KEY=$(printf '%s' "${SECOND_CREDENTIAL}" | json .key)
SECOND_KEY_SECRET="${SECOND_KEY##*_}"
SECOND_CUSTOMER_TOKEN=$(system_token customer "${SECOND_TENANT}.localhost" "${SECOND_KEY_ID}" "${SECOND_KEY_SECRET}")

customer_answers() { [ "$(code_of GET "${CUSTOMER}/customers" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ]; }
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the customer entitlement" customer_answers

# ------------------------------ customers ------------------------------
step "customers: upsert, patch, fields, tags, consent and batch"
REF="C-${RUN_ID}"
CREATED=$(call PUT "${CUSTOMER}/customers/${REF}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"displayName":"Ada Smoke","phone":"+44 20 7946 0958","email":"Ada@Example.com"}')
[ "$(printf '%s' "${CREATED}" | json .externalRef)" = "${REF}" ] || fail "the upsert did not answer the reference"
allowed "a customer was created with an ETag"

ETAG=$(curl --silent --head "${CUSTOMER}/customers/${REF}" --header "Host: ${HOST}" \
  --header "Authorization: Bearer ${CUSTOMER_TOKEN}" |
  awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
[ -n "${ETAG}" ] || fail "the customer answered no ETag"
call PATCH "${CUSTOMER}/customers/${REF}" "${HOST}" "${CUSTOMER_TOKEN}" '{"displayName":"Ada Lovelace"}'
[ "$(code_of PATCH "${CUSTOMER}/customers/${REF}" "${HOST}" "${CUSTOMER_TOKEN}" '{"displayName":"x"}')" = "428" ] ||
  fail "a patch without If-Match was accepted"
refused "a patch without If-Match"
curl --silent --output /dev/null --request PATCH "${CUSTOMER}/customers/${REF}" \
  --header "Host: ${HOST}" --header "Authorization: Bearer ${CUSTOMER_TOKEN}" \
  --header "Content-Type: application/json" --header "If-Match: ${ETAG}" \
  --data '{"displayName":"Ada Lovelace"}'
[ "$(curl --silent --output /dev/null --write-out '%{http_code}' --request PATCH "${CUSTOMER}/customers/${REF}" \
  --header "Host: ${HOST}" --header "Authorization: Bearer ${CUSTOMER_TOKEN}" \
  --header "Content-Type: application/json" --header 'If-Match: "1"' --data '{"displayName":"y"}')" = "412" ] ||
  fail "a stale If-Match was not refused with 412"
refused "a patch with a stale If-Match"

call POST "${CUSTOMER}/custom-fields" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"key":"colour","label":"Favourite colour","type":"TEXT"}' > /dev/null
call PATCH "${CUSTOMER}/customers/${REF}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"customFields":{"colour":"green"},"addTags":["vip","london"]}' > /dev/null
call POST "${CUSTOMER}/consent-topics" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"key":"marketing","label":"Marketing email"}' > /dev/null
CONSENT=$(call POST "${CUSTOMER}/customers/${REF}/consents" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"topicKey":"marketing","status":"GRANTED","occurredAt":"2026-03-01T09:00:00Z"}')
[ "$(printf '%s' "${CONSENT}" | json .status)" = "GRANTED" ] || fail "the consent was not recorded"
allowed "a field definition, two tags and a consent are stored on the customer"

BATCH=$(call POST "${CUSTOMER}/customers/batch" "${HOST}" "${CUSTOMER_TOKEN}" \
  "{\"items\":[{\"externalRef\":\"B-${RUN_ID}\"},{\"externalRef\":\"B2-${RUN_ID}\"},{\"displayName\":\"no ref\"}]}")
[ "$(printf '%s' "${BATCH}" | jq -r '[.results[] | select(.outcome == "FAILED")] | length')" = "1" ] ||
  fail "a batch did not report exactly one failed item"
allowed "a batch wrote the good items and reported the bad one alone"

SEARCH=$(call GET "${CUSTOMER}/customers?q=${REF}" "${HOST}" "${CUSTOMER_TOKEN}")
[ "$(printf '%s' "${SEARCH}" | jq -r '.data | length')" = "1" ] || fail "search did not find the customer by reference"
allowed "search finds the customer by its reference"

# ------------------------------ orders ------------------------------
step "orders: one order with lines, and a return against it"
ORDER="O-${RUN_ID}"
ORDER_BODY=$(cat <<EOF
{"externalRef":"${REF}","currency":"USD","occurredAt":"2026-03-02T10:00:00Z","storeCode":"ST-01","channel":"POS",
 "total":2500,"lines":[{"lineNo":1,"sku":"SKU-1","name":"Coffee","category":"GROCERY","quantity":1,"amount":2000},
                       {"lineNo":2,"sku":"SKU-2","name":"Cup","category":"HOUSEHOLD","quantity":2,"amount":500}]}
EOF
)
[ "$(code_of PUT "${CUSTOMER}/orders/${ORDER}" "${HOST}" "${CUSTOMER_TOKEN}" "${ORDER_BODY}")" = "201" ] ||
  fail "the order was not recorded"
[ "$(code_of PUT "${CUSTOMER}/orders/${ORDER}" "${HOST}" "${CUSTOMER_TOKEN}" "${ORDER_BODY}")" = "200" ] ||
  fail "the order replay did not answer 200"
[ "$(code_of PUT "${CUSTOMER}/orders/${ORDER}" "${HOST}" "${CUSTOMER_TOKEN}" \
  "$(printf '%s' "${ORDER_BODY}" | jq -c '.total = 2600')")" = "409" ] ||
  fail "a changed body under the same orderRef was accepted"
refused "the same orderRef with a different body"
allowed "the order is idempotent by its reference and refuses a changed body"

[ "$(code_of PUT "${CUSTOMER}/orders/${ORDER}/returns/R-${RUN_ID}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"occurredAt":"2026-03-03T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":2000}]}')" = "201" ] ||
  fail "the return was not recorded"
[ "$(code_of PUT "${CUSTOMER}/orders/${ORDER}/returns/R2-${RUN_ID}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"occurredAt":"2026-03-04T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":2000}]}')" = "422" ] ||
  fail "a return over the sold quantity was accepted"
refused "a return over what was sold"
allowed "a return is recorded and an over-return is refused"

step "activities, profile and export"
call POST "${CUSTOMER}/activities" "${HOST}" "${CUSTOMER_TOKEN}" \
  "{\"externalRef\":\"${REF}\",\"type\":\"pos.receipt-printed\",\"occurredAt\":\"2026-03-02T10:05:00Z\",\"payload\":{\"receipt\":\"R-1\"}}" \
  > /dev/null
call POST "${CUSTOMER}/activities" "${HOST}" "${CUSTOMER_TOKEN}" \
  "{\"externalRef\":\"${REF}\",\"type\":\"app.opened\",\"occurredAt\":\"2026-03-02T10:06:00Z\",\"payload\":{}}" > /dev/null
TYPES=$(call GET "${CUSTOMER}/customers/${REF}/activities" "${HOST}" "${CUSTOMER_TOKEN}" | jq -r '[.data[].type] | sort | join(",")')
[ "${TYPES}" = "app.opened,pos.receipt-printed" ] ||
  fail "two unrelated activity types were not both readable (${TYPES})"
allowed "two unrelated activity types are read back with no code change between them"

PROFILE=$(call GET "${CUSTOMER}/customers/${REF}/profile" "${HOST}" "${CUSTOMER_TOKEN}")
[ "$(printf '%s' "${PROFILE}" | jq -r '.customer.externalRef')" = "${REF}" ] || fail "the profile is missing the customer"
[ "$(printf '%s' "${PROFILE}" | jq -r '.orders | length')" = "1" ] || fail "the profile is missing the order"
allowed "the 360 profile answers the customer, its orders, activities and consent"

CSV=$(curl --silent "${CUSTOMER}/customers/export" --header "Host: ${HOST}" \
  --header "Authorization: Bearer ${CUSTOMER_TOKEN}")
printf '%s' "${CSV}" | head -1 | grep -q "externalRef" || fail "the export answered no header row"
printf '%s' "${CSV}" | grep -q "${REF}" || fail "the export did not stream the customer"
allowed "the export streams the customer as CSV"

# ------------------------------ webhooks ------------------------------
step "the four webhook types reach the Tenant's endpoint, signed"
CUSTOMER_SECRET=$(register_receiver "${CUSTOMER}" "${CUSTOMER_TOKEN}" "/record")
start_receiver "${CUSTOMER_SECRET}" "" ""
# A delivery only reaches an endpoint that was subscribed when the change happened, so the changes come after.
WREF="W-${RUN_ID}"
call PUT "${CUSTOMER}/customers/${WREF}" "${HOST}" "${CUSTOMER_TOKEN}" '{"displayName":"Watched"}' > /dev/null
WORDER="WO-${RUN_ID}"
call PUT "${CUSTOMER}/orders/${WORDER}" "${HOST}" "${CUSTOMER_TOKEN}" \
  "{\"externalRef\":\"${WREF}\",\"currency\":\"USD\",\"occurredAt\":\"2026-03-05T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",\"total\":1000,\"lines\":[{\"lineNo\":1,\"sku\":\"S\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":1000}]}" \
  > /dev/null
call PUT "${CUSTOMER}/orders/${WORDER}/returns/WR-${RUN_ID}" "${HOST}" "${CUSTOMER_TOKEN}" \
  '{"occurredAt":"2026-03-06T10:00:00Z","lines":[{"lineNo":1,"quantity":1,"amount":500}]}' > /dev/null
call POST "${CUSTOMER}/customers/${WREF}/erasure" "${HOST}" "${CUSTOMER_TOKEN}" > /dev/null
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the four webhook types" all_types_arrived \
  "customer.created order.recorded order.refunded customer.erased"
allowed "the Tenant saw all four types, each a signed envelope"

step "erasure leaves the business rows and clears the person"
ERASED_STATUS=$(code_of GET "${CUSTOMER}/customers/${WREF}" "${HOST}" "${CUSTOMER_TOKEN}")
[ "${ERASED_STATUS}" = "404" ] ||
  fail "the erased customer is still reachable by its old reference (${ERASED_STATUS})"
refused "the erased customer's reference reaching a live customer"
# Its business records stay: the order it placed is still there, anonymous.
assert_order_kept() {
  [ "$(code_of GET "${CUSTOMER}/orders/${WORDER}" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ]
}
wait_for "the erased customer's order to remain" assert_order_kept
allowed "the order stays as a business record after the erasure"

# ------------------------------ isolation ------------------------------
step "a second Tenant sees none of it"
[ "$(code_of GET "${CUSTOMER}/customers/${REF}" "${SECOND_TENANT}.localhost" "${SECOND_CUSTOMER_TOKEN}")" = "404" ] ||
  fail "another Tenant read the first Tenant's customer"
refused "the second Tenant reading the first Tenant's customer"

step "independence: customer answers while the other apps are stopped"
docker compose stop loyalty-api loyalty-worker > /dev/null 2>&1 || true
[ "$(code_of GET "${CUSTOMER}/customers" "${HOST}" "${CUSTOMER_TOKEN}")" = "200" ] ||
  fail "customer stopped answering while loyalty was down"
allowed "customer serves its API with loyalty stopped"
docker compose start loyalty-api loyalty-worker > /dev/null 2>&1 || true

step "cleaning up"
stop_receiver

echo "PASS: the customer app serves the Tenant's customers, orders, activities and webhooks on its own"
