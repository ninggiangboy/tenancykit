#!/usr/bin/env bash
# Plays the Tenant System against a running stack: catalog, an interval and a capacity offering, the public gate, the
# waiting room, the waitlist, reschedule, a series and the invariant check. Run from the repository root with the
# compose stack up.
set -euo pipefail

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

TENANT_CODE="${SMOKE_TENANT:-booking-smoke}"
smoke_bootstrap "booking" "${TENANT_CODE}"
BOOKING="${PROXY}/booking/api/v1"
PUBLIC="${PROXY}/booking/p"
GATE_ORIGIN="https://${TENANT_CODE}.example"

step "configuring the public gate"
call PUT "${BOOKING}/settings/gate" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"allowedOrigins\":[\"${GATE_ORIGIN}\"],\"maxTokenTtlSeconds\":600}" > /dev/null
allowed "the gate allows ${GATE_ORIGIN} and a ten-minute token"

step "creating a resource, its hours and two offerings"
RESOURCE=$(call POST "${BOOKING}/resources" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"code\":\"room-${RUN_ID}\",\"name\":\"Room ${RUN_ID}\",\"zone\":\"UTC\"}" | json .id)
HOURS='{"hours":[{"weekday":1,"opens":"00:00","closes":"24:00"},{"weekday":2,"opens":"00:00","closes":"24:00"},
{"weekday":3,"opens":"00:00","closes":"24:00"},{"weekday":4,"opens":"00:00","closes":"24:00"},
{"weekday":5,"opens":"00:00","closes":"24:00"},{"weekday":6,"opens":"00:00","closes":"24:00"},
{"weekday":7,"opens":"00:00","closes":"24:00"}]}'
call PUT "${BOOKING}/resources/${RESOURCE}/working-hours" "${HOST}" "${BOOKING_TOKEN}" "${HOURS}" > /dev/null

INTERVAL=$(call POST "${BOOKING}/offerings" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"code\":\"appointment-${RUN_ID}\",\"name\":\"Appointment\",\"kind\":\"INTERVAL\",\"durationMin\":60,
    \"slotStep\":30,\"horizonDays\":30}" | json .id)
call PUT "${BOOKING}/offerings/${INTERVAL}/requirements" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"requirements\":[{\"slotCode\":\"staff\",\"resourceId\":\"${RESOURCE}\"}]}" > /dev/null

CAPACITY=$(call POST "${BOOKING}/offerings" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"code\":\"rush-${RUN_ID}\",\"name\":\"Rush\",\"kind\":\"CAPACITY\",\"durationMin\":60,\"slotStep\":60,
    \"horizonDays\":30,\"queueMode\":\"REQUIRED\",\"admitPerSecond\":50,\"admitWindowSeconds\":120}" | json .id)
DTSTART=$(date -u -v+1d '+%Y-%m-%dT18:00:00' 2>/dev/null || date -u -d '+1 day 18:00' '+%Y-%m-%dT%H:%M:%S')
SESSION=$(call POST "${BOOKING}/session-series" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"offeringId\":\"${CAPACITY}\",\"zone\":\"UTC\",\"dtstart\":\"${DTSTART}\",
    \"rule\":[\"RRULE:FREQ=DAILY;COUNT=2\"],\"durationMinutes\":60,\"capacity\":2}" | json '.sessions[0].id')
allowed "resource, interval offering ${INTERVAL}, rush offering ${CAPACITY}, session ${SESSION}"

step "minting a customer token and holding through the gate"
CUSTOMER="cust-${RUN_ID}"
CUSTOMER_TOKEN=$(call POST "${BOOKING}/customer-tokens" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"customerRef\":\"${CUSTOMER}\",\"scopes\":[\"browse\",\"hold\",\"waitlist\"],\"ttlSeconds\":600}" | json .token)
call GET "${PUBLIC}/offerings/${INTERVAL}" "${HOST}" "${CUSTOMER_TOKEN}" > /dev/null
HOLD=$(call POST "${PUBLIC}/bookings" "${HOST}" "${CUSTOMER_TOKEN}" \
  "{\"offeringId\":\"${INTERVAL}\",\"start\":\"$(date -u -v+2d '+%Y-%m-%dT09:00:00' 2>/dev/null || date -u -d '+2 days 09:00' '+%Y-%m-%dT%H:%M:%S')\",
    \"durationMinutes\":60}" | json .id)
[ "$(code_of POST "${BOOKING}/bookings/${HOLD}/confirm" "${HOST}" "${BOOKING_TOKEN}" '{}')" = "200" ] ||
  fail "the Tenant could not confirm the gate hold"
allowed "the gate hold ${HOLD} was confirmed by the Tenant"

step "entering the waiting room and holding once admitted"
TICKET=$(call POST "${PUBLIC}/queue" "${HOST}" "${CUSTOMER_TOKEN}" \
  "{\"offeringId\":\"${CAPACITY}\",\"sessionId\":\"${SESSION}\"}" | json .ticketToken)
held_with_ticket() {
  local status code
  status=$(curl --silent --show-error "${PUBLIC}/queue/status" \
    --header "Host: ${HOST}" --header "Authorization: Bearer ${CUSTOMER_TOKEN}" \
    --header "X-Queue-Ticket: ${TICKET}" | jq -r .status)
  [ "${status}" = "ADMITTED" ] || return 1
  code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --request POST "${PUBLIC}/bookings" \
    --header "Host: ${HOST}" --header "Authorization: Bearer ${CUSTOMER_TOKEN}" \
    --header "Content-Type: application/json" --header "X-Queue-Ticket: ${TICKET}" \
    --data "{\"offeringId\":\"${CAPACITY}\",\"sessionId\":\"${SESSION}\",\"poolCode\":\"default\",\"qty\":1}")
  [ "${code}" = "201" ]
}
wait_for "admission to the rush session" held_with_ticket
allowed "the ticket was admitted and funded one hold"

step "joining the waitlist of a full rush session, then cancelling into an offer"
WAITER="cust-wait-${RUN_ID}"
WAITER_TOKEN=$(call POST "${BOOKING}/customer-tokens" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"customerRef\":\"${WAITER}\",\"scopes\":[\"waitlist\"],\"ttlSeconds\":600}" | json .token)
ENTRY=$(call POST "${PUBLIC}/waitlist" "${HOST}" "${WAITER_TOKEN}" \
  "{\"offeringId\":\"${CAPACITY}\",\"qty\":1,\"sessionId\":\"${SESSION}\",\"poolCode\":\"default\"}" | json .id)
RUSH_BOOKINGS=$(call GET "${BOOKING}/bookings?filter[source][eq]=GATE" "${HOST}" "${BOOKING_TOKEN}" | jq -r --arg s "${SESSION}" \
  '.data | map(select(.sessionId == $s and .status == "HELD")) | .[0].id')
[ -n "${RUSH_BOOKINGS}" ] || fail "no gate hold to release for the waitlist offer"
[ "$(code_of DELETE "${PUBLIC}/bookings/${RUSH_BOOKINGS}" "${HOST}" "${CUSTOMER_TOKEN}")" = "204" ] ||
  fail "the gate hold could not be released"
waitlist_offered() {
  local status
  status=$(call GET "${PUBLIC}/waitlist/${ENTRY}" "${HOST}" "${WAITER_TOKEN}" | jq -r .status)
  [ "${status}" = "OFFERED" ]
}
wait_for "the waitlist offer" waitlist_offered
allowed "the freed place offered waitlist entry ${ENTRY}"

step "rescheduling the confirmed appointment and creating a series"
NEW_START=$(date -u -v+2d '+%Y-%m-%dT11:00:00' 2>/dev/null || date -u -d '+2 days 11:00' '+%Y-%m-%dT%H:%M:%S')
[ "$(code_of POST "${BOOKING}/bookings/${HOLD}/reschedule" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"start\":\"${NEW_START}\",\"durationMinutes\":60}")" = "200" ] ||
  fail "the appointment could not be rescheduled"
call POST "${BOOKING}/series" "${HOST}" "${BOOKING_TOKEN}" \
  "{\"offeringId\":\"${INTERVAL}\",\"customerRef\":\"series-${RUN_ID}\",\"zone\":\"UTC\",
    \"dtstart\":\"${NEW_START}\",\"rule\":[\"RRULE:FREQ=WEEKLY;COUNT=3\"],\"durationMinutes\":60,
    \"mode\":\"SKIP_CONFLICTS\"}" > /dev/null
allowed "the appointment moved and a three-occurrence series was created"

step "checking the claim invariants"
ops/smoke/booking-invariants.sh
allowed "booking smoke finished"
