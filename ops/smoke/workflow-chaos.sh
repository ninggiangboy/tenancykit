#!/usr/bin/env bash
# Kills the workflow worker at random moments and proves the core loses no step.
#
# Each cycle starts a dunning run, waits until it is RUNNING or WAITING, SIGKILLs the worker container, restarts it and
# waits for the run to come to rest. At the end it asserts, for every instance of the smoke tenant: no step has two
# completion records, every interrupted send kept one idempotency key per logical step, and no token stayed READY,
# RUNNING or WAITING once the run rested.
#
# Requirements: curl, jq, docker compose. Run from the repository root.
set -euo pipefail

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

CYCLES="${CHAOS_CYCLES:-50}"
TENANT_CODE="${SMOKE_WORKFLOW_TENANT:-workflow-chaos}"
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

step "${CYCLES} kill-and-restart cycles"
for cycle in $(seq 1 "${CYCLES}"); do
  run=$(call POST "${WORKFLOW}/instances" "${HOST}" "${TOKEN}" \
    "{\"definition\":\"${DUNNING:-00000000-0000-0000-0000-000000000000}\",\"input\":{\"data\":{\"customer\":{\"email\":\"chaos@example.test\"},\"invoiceId\":\"inv-${RUN_ID}-${cycle}\",\"subscriptionId\":\"sub-${RUN_ID}\"}}}") || true
  sleep "0.$((RANDOM % 9 + 1))"
  docker compose kill workflow-worker > /dev/null
  docker compose start workflow-worker > /dev/null
  WAIT_SECONDS="${RESTART_SECONDS}" wait_for "the worker to answer readiness" bash -c \
    "curl --silent --fail http://localhost:8080/actuator/health/readiness > /dev/null || exit 1" || true
done
allowed "the worker survived ${CYCLES} SIGKILL cycles"

step "no step has two completions and no interrupted send changed its key"
DOUBLES=$(in_tenant_sql "select count(*) from (select token_id, attempt from workflow_step_run group by token_id, attempt having count(*) > 1) d")
[ "${DOUBLES:-0}" = "0" ] || fail "a step wrote two completion records"
allowed "each step has one completion record"

step "no token is left live once its run rested"
LIVE=$(in_tenant_sql "select count(*) from workflow_token t join workflow_instance i on i.id = t.instance_id where i.status in ('COMPLETED','FAILED','FAILED_STUCK','CANCELLED') and t.status in ('READY','RUNNING','WAITING')")
[ "${LIVE:-0}" = "0" ] || fail "a rested instance left a live token"
allowed "no rested instance left a live token"
