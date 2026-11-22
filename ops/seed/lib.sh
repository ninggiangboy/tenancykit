#!/usr/bin/env bash
# Helpers shared by the seed modules. Sourced, never executed.
#
# It builds on the smoke harness (the Keycloak helpers, `call`, `json`, the Platform administrator) and adds what a
# seeder needs and a smoke test does not: calls that report instead of abort, so one refused optional step never
# stops the run, and a ledger of what was done.

# shellcheck disable=SC1091
. ops/smoke/lib/harness.sh

# The realm the compose stack imports is `billing` (ops/keycloak/realm-export.json). A stale KEYCLOAK_REALM in a
# developer's environment file would otherwise send every Keycloak call to a realm that does not exist, so it is pinned.
REALM="${SEED_KEYCLOAK_REALM:-billing}"
SEED_PASSWORD="${SEED_PASSWORD:-Demo-1234}"
SEED_OUT="${SEED_OUT:-ops/seed/.out}"
mkdir -p "${SEED_OUT}"

NOTES=()
HTTP_CODE=""
HTTP_BODY=""

note() { NOTES+=("$*"); printf '   ! %s\n' "$*" >&2; }
info() { printf '   %s\n' "$*"; }

# One call. The answer lands in HTTP_CODE and HTTP_BODY, so a caller decides what a refusal means.
#   req METHOD URL HOST TOKEN [BODY] [IDEMPOTENCY_KEY] [IF_MATCH]
req() {
  local method="$1" url="$2" host="$3" token="$4" body="${5:-}" key="${6:-}" match="${7:-}" out
  out=$(curl --globoff --silent --show-error --max-time 60 --write-out '\n%{http_code}' \
    --request "${method}" "${url}" \
    --header "Host: ${host}" \
    --header "Authorization: Bearer ${token}" \
    --header "Content-Type: application/json" \
    ${key:+--header "Idempotency-Key: ${key}"} \
    ${match:+--header "If-Match: ${match}"} \
    ${body:+--data "${body}"} 2>&1) || true
  HTTP_CODE="${out##*$'\n'}"
  HTTP_BODY="${out%$'\n'*}"
  case "${HTTP_CODE}" in [0-9][0-9][0-9]) ;; *) HTTP_CODE=000 ;; esac
}

ok() { [[ "${HTTP_CODE}" == 2* ]]; }

# The strong version of a step the rest depend on: any answer but 2xx stops the run, with the server's own words.
must() {
  local what="$1"
  shift
  req "$@"
  ok || fail "${what}: ${HTTP_CODE} ${HTTP_BODY:0:300}"
}

# A step the demo can live without: a refusal becomes a note.
try() {
  local what="$1"
  shift
  req "$@"
  ok && return 0
  note "${what}: ${HTTP_CODE} ${HTTP_BODY:0:160}"
  return 1
}

# The current ETag of a resource, for the If-Match header its update asks for. Platform and the apps quote the version.
etag_of() {
  curl --globoff --silent --head "$1" --header "Host: $2" --header "Authorization: Bearer $3" |
    awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r'
}

# jq on the last answer; empty when the path is absent.
pick() { printf '%s' "${HTTP_BODY}" | jq -r "$1 // empty" 2>/dev/null; }

# An ISO instant N days from now (negative for the past), portable across macOS and Linux.
iso_days() {
  date -u -v"${1}"d '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d "${1} days" '+%Y-%m-%dT%H:%M:%SZ'
}
iso_date() {
  date -u -v"${1}"d '+%Y-%m-%d' 2>/dev/null || date -u -d "${1} days" '+%Y-%m-%d'
}

# ------------------------------ calling one app as a tenant ------------------------------
# use_app <tenant> <app> <base url>   then   acall METHOD /path [BODY] [IDEMPOTENCY_KEY] [IF_MATCH]
# A call is made as the tenant's own software (its Tenant System token). What a Tenant System may not do is the tenant
# administrator's, so a refusal is retried as the administrator, exactly as the console would do it.

use_app() {
  A_CODE="$1" A_APP="$2" A_BASE="$3"
  refresh_app_tokens
  # A new tenant reaches an app through that app's replica, which catches up a moment after Platform creates it: until
  # then the app answers 401 to the tenant's Tenant System.
  local path
  case "${A_APP}" in
    loyalty) path=/program ;;
    booking | mailroom) path=/settings ;;
    survey) path=/surveys ;;
    attribution) path=/campaigns ;;
    workflow) path=/definitions ;;
    *) path=/customers ;;
  esac
  app_replied() {
    req GET "${A_BASE}${path}" "${A_CODE}.localhost" "${A_SYS}"
    [ "${HTTP_CODE}" != 401 ] && [ "${HTTP_CODE}" != 000 ] && [ "${HTTP_CODE}" != 503 ]
  }
  WAIT_SECONDS="${RESTART_SECONDS}" wait_for "${A_APP} to accept tenant ${A_CODE}" app_replied
}

refresh_app_tokens() {
  A_SYS=$(system_token_for "${A_CODE}" "${A_APP}")
  A_ADMIN=$(tenant_token "${A_CODE}")
}

acall() {
  local method="$1" path="$2" body="${3:-}" key="${4:-}" match="${5:-}"
  req "${method}" "${A_BASE}${path}" "${A_CODE}.localhost" "${A_SYS}" "${body}" "${key}" "${match}"
  if [ "${HTTP_CODE}" = 401 ] || [ "${HTTP_CODE}" = 403 ]; then
    req "${method}" "${A_BASE}${path}" "${A_CODE}.localhost" "${A_ADMIN}" "${body}" "${key}" "${match}"
    if [ "${HTTP_CODE}" = 401 ]; then
      refresh_app_tokens
      req "${method}" "${A_BASE}${path}" "${A_CODE}.localhost" "${A_ADMIN}" "${body}" "${key}" "${match}"
    fi
  fi
}

# A step the demo can live without: a refusal becomes a note. A conflict on a create is the answer to a second run (what
# the first run made is still there), so it is not one.
acall_try() {
  local what="$1"
  shift
  acall "$@"
  ok && return 0
  [ "${HTTP_CODE}" = 409 ] || note "${A_APP}/${A_CODE} ${what}: ${HTTP_CODE} ${HTTP_BODY:0:140}"
  return 1
}

# A step the rest depend on.
acall_need() {
  local what="$1"
  shift
  acall "$@"
  ok || fail "${A_APP}/${A_CODE} ${what}: ${HTTP_CODE} ${HTTP_BODY:0:300}"
}

# The same call as the tenant's administrator, for what a Tenant System is deliberately not granted.
acall_admin() {
  local method="$1" path="$2" body="${3:-}" key="${4:-}" match="${5:-}"
  req "${method}" "${A_BASE}${path}" "${A_CODE}.localhost" "${A_ADMIN}" "${body}" "${key}" "${match}"
}


# An item of a list addressed by its `code`: found, or created from the body. The id lands in ENSURED_ID.
# usage: ensure_code /resources room-1 '{"code":"room-1",...}'
ensure_code() {
  local path="$1" code="$2" body="$3"
  ENSURED_ID=""
  acall GET "${path}?limit=100"
  ENSURED_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg c "${code}" '(.data // .) | map(select(.code == $c))[0].id // empty' 2>/dev/null)
  [ -z "${ENSURED_ID}" ] || return 0
  acall_try "create ${code}" POST "${path}" "${body}" && ENSURED_ID=$(pick '.id')
  [ -n "${ENSURED_ID}" ]
}
