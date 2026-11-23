#!/usr/bin/env bash
# Workflow: the tenant's own credential registered with the engine, a connection, the four sample definitions published,
# a task group, and instances in different states: parked on a wait, finished, and waiting for a human to decide.
# The definitions are the samples the workflow tests keep (backend/services/apps/workflow/.../samples). Sourced by seed.sh.

WORKFLOW_SAMPLES="backend/services/apps/workflow/apps/app-boot/src/test/resources/samples"

# wf_publish <code> <sample file>   (definition id in DEFINITION_ID; an existing code is reused)
wf_publish() {
  local code="$1" file="$2" etag
  DEFINITION_ID=""
  acall GET "/definitions?limit=100"
  DEFINITION_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg c "${code}" '(.data // []) | map(select(.code == $c))[0].id // empty' 2>/dev/null)
  [ -z "${DEFINITION_ID}" ] || { info "definition ${code} exists"; return 0; }
  acall_try "definition ${code}" POST /definitions "{\"code\":\"${code}\",\"name\":\"${code}\"}" || return 1
  DEFINITION_ID=$(pick '.id')
  etag=$(etag_of "${A_BASE}/definitions/${DEFINITION_ID}" "${A_CODE}.localhost" "${A_SYS}")
  acall_try "draft ${code}" PUT "/definitions/${DEFINITION_ID}/draft" "$(jq -c '{graph: .}' "${WORKFLOW_SAMPLES}/${file}.json")" "" "${etag}" || return 1
  etag=$(etag_of "${A_BASE}/definitions/${DEFINITION_ID}" "${A_CODE}.localhost" "${A_SYS}")
  acall_try "publish ${code}" POST "/definitions/${DEFINITION_ID}/publish" "" "" "${etag}" || return 1
}

wf_start() {
  # usage: wf_start <definition id> <key> <input json>   (instance id in INSTANCE_ID)
  INSTANCE_ID=""
  acall_try "instance $2" POST /instances "{\"definition\":\"$1\",\"input\":$3,\"correlationKey\":\"$2\"}" "seed-workflow-$2" && INSTANCE_ID=$(pick '.id')
  [ -n "${INSTANCE_ID}" ]
}

seed_workflow() {
  step "Workflow: acme"
  use_app acme workflow "${PROXY}/workflow/system/v1"
  local file key_id secret
  file="$(credential_file acme)"
  key_id=$(jq -r .keyId "${file}")
  secret=$(jq -r .secret "${file}")

  # the credential the engine mints its tokens with: the tenant's, not the suite's
  acall_try "platform credential" PUT /platform-credential "{\"clientId\":\"${key_id}\",\"secret\":\"${secret}\"}" || true
  acall_try "connection" POST /connections '{"name":"smokeEcho","kind":"BEARER","allowedHost":"workflow-echo:80","secret":"demo","headers":{}}' || true

  local dunning="" refund="" points="" erasure=""
  wf_publish payment-dunning payment-dunning && dunning="${DEFINITION_ID}"
  wf_publish refund-approval refund-approval && refund="${DEFINITION_ID}"
  wf_publish points-nudge points-nudge && points="${DEFINITION_ID}"
  wf_publish erasure-fanout erasure-fanout && erasure="${DEFINITION_ID}"

  # the managers group holds the tenant's manager account, whose id is the Platform user's
  local manager_id
  req GET "${PROXY}/platform/api/v1/users?filter[email][eq]=manager@acme.localhost&limit=1" acme.localhost "${A_ADMIN}"
  manager_id=$(pick '.data[0].id')
  [ -z "${manager_id}" ] || acall_try "task group" POST /task-groups "{\"code\":\"managers\",\"name\":\"Managers\",\"members\":[\"${manager_id}\"]}" || true

  [ -z "${dunning}" ] || wf_start "${dunning}" dunning-1001 '{"data":{"customer":{"email":"linh.tran@example.test"},"invoiceId":"inv-1001","subscriptionId":"sub-1001"}}' || true
  [ -z "${dunning}" ] || wf_start "${dunning}" dunning-1002 '{"data":{"customer":{"email":"bao.dang@example.test"},"invoiceId":"inv-1002","subscriptionId":"sub-1002"}}' || true
  [ -z "${points}" ] || wf_start "${points}" nudge-1 '{"data":{"member":{"email":"minh.nguyen@example.test","externalRef":"C-002"}}}' || true
  [ -z "${erasure}" ] || wf_start "${erasure}" erase-1 '{"data":{"externalRef":"C-099"}}' || true
  # two refund requests: one left for a human, one decided
  [ -z "${refund}" ] || wf_start "${refund}" refund-2001 '{"data":{"paymentId":"pay-2001","amount":1500,"customer":{"email":"hoa.pham@example.test"}}}' || true
  [ -z "${refund}" ] || wf_start "${refund}" refund-2002 '{"data":{"paymentId":"pay-2002","amount":900,"customer":{"email":"anh.vo@example.test"}}}' || true

  # a task needs a moment to appear; the second is approved on behalf of the manager
  sleep 4
  acall GET "/tasks?limit=50"
  local task_id
  task_id=$(printf '%s' "${HTTP_BODY}" | jq -r '(if type == "array" then . else (.data // []) end) | map(select(.status == "OPEN"))[0].id // empty' 2>/dev/null)
  if [ -n "${task_id}" ] && [ -n "${manager_id}" ]; then
    local etag
    etag=$(etag_of "${A_BASE}/tasks/${task_id}" "${A_CODE}.localhost" "${A_SYS}")
    acall_try "complete task" POST "/tasks/${task_id}/complete" "{\"decision\":\"approve\",\"completedBy\":\"${manager_id}\"}" "" "${etag}" || true
  fi
}
