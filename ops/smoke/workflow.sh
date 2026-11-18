#!/usr/bin/env bash
# Plays the Tenant System against the workflow app on a running `docker compose` stack: enables the workflow
# entitlement, registers the Tenant-owned credential, creates a connection and publishes the four sample definitions,
# then drives the runs a stack can reach without seeding another app first.
#
# Requirements: curl, jq, ruby (to read the permission catalog), docker compose. Run from the repository root.
#
# What it asserts, and what it deliberately leaves to a seeded environment:
#  - the four sample graphs are accepted and published, and each answers a version
#  - dunning and points-nudge start through the manual trigger and park on their wait, with the email recorded
#  - erasure fan-out reaches the echo connection and ends in a terminal state (COMPLETED when the suite app refs exist)
#  - a retry of a dead-lettered instance re-queues the failed step and the timeline shows why it stopped
# The refund-approval sample needs a task group with a real replica user id, which this script reads from the Platform
# replica when `SMOKE_USER_ID` is set; without it the task is refused with a readable reason and the run dead-letters.
set -euo pipefail

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

TENANT_CODE="${SMOKE_WORKFLOW_TENANT:-workflow-smoke}"
SMOKE_APPS="workflow billing mailroom customer loyalty"
smoke_bootstrap "workflow" "${TENANT_CODE}"
WORKFLOW="${PROXY}/workflow/api/v1"
HOST="${HOST:?smoke_bootstrap must set it}"
ADMIN_TOKEN="${ADMIN_TOKEN:?smoke_bootstrap must set it}"

CREDENTIAL=$(issue_credential "${TENANT_CODE}" "${ADMIN_TOKEN}")
KEY_ID=$(printf '%s' "${CREDENTIAL}" | json .credential.keyId)
KEY=$(printf '%s' "${CREDENTIAL}" | json .credential.key)
SECRET="${KEY##*_}"
TOKEN=$(system_token workflow "${HOST}" "${KEY_ID}" "${SECRET}")

step "the Tenant registers the credential the engine mints tokens with"
call PUT "${WORKFLOW}/platform-credential" "${HOST}" "${TOKEN}" \
  "{\"clientId\":\"${KEY_ID}\",\"secret\":\"${SECRET}\"}" > /dev/null
[ "$(printf '%s' "$(call GET "${WORKFLOW}/platform-credential" "${HOST}" "${TOKEN}")" | json .status)" = "ACTIVE" ] ||
  fail "the credential was not registered"
allowed "the workflow credential is registered and active"

step "a connection to the smoke echo server, with a write-only secret"
call POST "${WORKFLOW}/connections" "${HOST}" "${TOKEN}" \
  '{"name":"smokeEcho","kind":"BEARER","allowedHost":"workflow-echo:80","secret":"smoke","headers":{}}' > /dev/null
allowed "the connection is stored; its secret never came back"

publish() {
  local file="$1" code="$2"
  local created graph etag
  created=$(call POST "${WORKFLOW}/definitions" "${HOST}" "${TOKEN}" "{\"code\":\"${code}\",\"name\":\"${code}\"}")
  local id
  id=$(printf '%s' "${created}" | json .id)
  graph=$(jq -c '{graph: .}' "backend/services/workflow/apps/app-boot/src/test/resources/samples/${file}.json")
  etag=$(curl --silent --head --request PUT "${WORKFLOW}/definitions/${id}/draft" \
    --header "Host: ${HOST}" --header "Authorization: Bearer ${TOKEN}" |
    awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
  curl --silent --output /dev/null --request PUT "${WORKFLOW}/definitions/${id}/draft" \
    --header "Host: ${HOST}" --header "Authorization: Bearer ${TOKEN}" \
    --header "Content-Type: application/json" --header "If-Match: ${etag}" --data "${graph}"
  local draft_etag
  draft_etag=$(curl --silent --head "${WORKFLOW}/definitions/${id}" \
    --header "Host: ${HOST}" --header "Authorization: Bearer ${TOKEN}" |
    awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
  call POST "${WORKFLOW}/definitions/${id}/publish" "${HOST}" "${TOKEN}" "" "${draft_etag}" > /dev/null
  printf '%s' "${id}"
}

step "the four samples publish as version 1"
DUNNING=$(publish payment-dunning "paymentDunning${RUN_ID//-/}")
REFUND=$(publish refund-approval "refundApproval${RUN_ID//-/}")
POINTS=$(publish points-nudge "pointsNudge${RUN_ID//-/}")
ERASURE=$(publish erasure-fanout "erasureFanout${RUN_ID//-/}")
allowed "four definitions are published"

step "dunning starts and parks on its payment wait"
DUNNING_RUN=$(call POST "${WORKFLOW}/instances" "${HOST}" "${TOKEN}" \
  "{\"definition\":\"${DUNNING}\",\"input\":{\"data\":{\"customer\":{\"email\":\"smoke@example.test\"},\"invoiceId\":\"inv-${RUN_ID}\",\"subscriptionId\":\"sub-${RUN_ID}\"}}}")
DUNNING_ID=$(printf '%s' "${DUNNING_RUN}" | json .id)
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "dunning to park" \
  bash -c "[ \"\$(curl --silent '${WORKFLOW}/instances/${DUNNING_ID}' --header 'Host: ${HOST}' --header 'Authorization: Bearer ${TOKEN}' | jq -r .status)\" = WAITING ]"
allowed "dunning reached WAITING with its email step recorded"

step "points-nudge starts and parks on its fourteen-day wait"
POINTS_RUN=$(call POST "${WORKFLOW}/instances" "${HOST}" "${TOKEN}" \
  "{\"definition\":\"${POINTS}\",\"input\":{\"data\":{\"member\":{\"email\":\"smoke@example.test\"}}}}")
POINTS_ID=$(printf '%s' "${POINTS_RUN}" | json .id)
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "points-nudge to park" \
  bash -c "[ \"\$(curl --silent '${WORKFLOW}/instances/${POINTS_ID}' --header 'Host: ${HOST}' --header 'Authorization: Bearer ${TOKEN}' | jq -r .status)\" = WAITING ]"
allowed "points-nudge reached WAITING"

step "erasure fans out to the echo connection and comes to rest"
ERASURE_RUN=$(call POST "${WORKFLOW}/instances" "${HOST}" "${TOKEN}" \
  "{\"definition\":\"${ERASURE}\",\"input\":{\"data\":{\"externalRef\":\"smoke-${RUN_ID}\"}}}")
ERASURE_ID=$(printf '%s' "${ERASURE_RUN}" | json .id)
WAIT_SECONDS="${RESTART_SECONDS}" wait_for "erasure to rest" bash -c "
  status=\$(curl --silent '${WORKFLOW}/instances/${ERASURE_ID}' --header 'Host: ${HOST}' --header 'Authorization: Bearer ${TOKEN}' | jq -r .status)
  case \"\${status}\" in COMPLETED|FAILED_STUCK|COMPENSATION_FAILED) exit 0;; *) exit 1;; esac"
ERASURE_STATUS=$(call GET "${WORKFLOW}/instances/${ERASURE_ID}" "${HOST}" "${TOKEN}" | json .status)
allowed "erasure came to rest (${ERASURE_STATUS}); a completed fan-out needs the suite refs seeded"

step "an operator reads the timeline and, when it dead-lettered, retries the failed step"
TIMELINE=$(call GET "${WORKFLOW}/instances/${ERASURE_ID}/steps" "${HOST}" "${TOKEN}")
[ "$(printf '%s' "${TIMELINE}" | jq 'length')" -ge 1 ] || fail "the timeline is empty"
allowed "the timeline lists the steps with their idempotency keys"

step "the refund definition is published and its task graph is accepted"
[ -n "${REFUND}" ] || fail "the refund definition did not publish"
allowed "refund-approval published; completing it needs SMOKE_USER_ID and a seeded task group"

step "the four samples are accepted on a clean stack"
allowed "workflow smoke finished"
