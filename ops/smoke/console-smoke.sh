#!/usr/bin/env bash
# Proves the console's edge and the publish flow against the running compose stack:
#
#   1. two versions of remote-billing are built and published, each into its own immutable directory
#   2. the newer manifest answers through Caddy on a tenant host and on the platform host
#   3. the first version's files are still served after the second deploy
#   4. the headers the CSP and the cache rules depend on are on the response
#   5. /config.json is rendered and is not cached
#   6. the registry refuses an entry path outside /console/remotes/<name>/<version>/
#
# Requires the stack up (`docker compose up -d`), frontend dependencies installed, and the frontend-ci secret in the
# environment (FRONTEND_CI_SERVICE_CLIENT_SECRET). Requirements: curl, jq, node, git, pnpm, docker compose.
set -euo pipefail

PROXY="${PROXY_URL:-http://localhost:8090}"
PLATFORM_HOST="${PLATFORM_HOSTNAME:-platform.localhost}"
TENANT_HOST="${CONSOLE_SMOKE_HOST:-console-smoke.localhost}"
# Platform answers only on its own hostname, so the API calls carry that host rather than localhost.
API="${API_URL:-http://${PLATFORM_HOST}:8090}"
REMOTE="billing"
FIRST_VERSION="smoke-a"
SECOND_VERSION="smoke-b"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRONTEND="${REPO_ROOT}/frontend"
DIST="${FRONTEND}/remotes/${REMOTE}/dist"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

step() {
  echo "== $*"
}

expect_header() {
  local headers="$1" name="$2" expected="$3"
  local actual
  actual="$(printf '%s' "${headers}" | tr -d '\r' | awk -F': ' -v n="${name}" 'tolower($1) == n { print $2 }' | head -1)"
  if [ -z "${actual}" ]; then
    fail "no ${name} header"
  fi
  case "${actual}" in
    *"${expected}"*) ;;
    *) fail "${name} is '${actual}', which does not hold '${expected}'" ;;
  esac
}

build() {
  step "building ${REMOTE} ${1}"
  REMOTE_VERSION="${1}" pnpm --dir "${FRONTEND}" --filter "@suite/remote-${REMOTE}" build >/dev/null
  [ -f "${DIST}/console.json" ] || fail "the build wrote no console.json"
}

publish() {
  step "publishing ${1}"
  REMOTE_VERSION_OVERRIDE="${1}" "${REPO_ROOT}/ops/console/deploy-remote.sh" "${REMOTE}" "${DIST}"
}

build "${FIRST_VERSION}"
publish "${FIRST_VERSION}"
build "${SECOND_VERSION}"
publish "${SECOND_VERSION}"

for host in "${TENANT_HOST}" "${PLATFORM_HOST}"; do
  step "the published manifest answers on ${host}"
  for version in "${SECOND_VERSION}" "${FIRST_VERSION}"; do
    path="/console/remotes/${REMOTE}/${version}/mf-manifest.json"
    curl --silent --show-error --fail --output /dev/null -H "Host: ${host}" "${PROXY}${path}" ||
      fail "${path} does not answer on ${host}"

    headers="$(curl --silent --show-error --head -H "Host: ${host}" "${PROXY}${path}")"
    expect_header "${headers}" "cache-control" "public, max-age=31536000, immutable"
    expect_header "${headers}" "content-security-policy" "script-src 'self'"
    expect_header "${headers}" "content-security-policy" "frame-ancestors 'none'"
    expect_header "${headers}" "x-content-type-options" "nosniff"
    expect_header "${headers}" "referrer-policy" "no-referrer"
  done
done

step "the runtime configuration is rendered and never cached"
config="$(curl --silent --show-error --fail -H "Host: ${TENANT_HOST}" "${PROXY}/console/config.json")"
printf '%s' "${config}" | jq -e '.authority and .clientId' >/dev/null || fail "/console/config.json has no authority or client id"
expect_header "$(curl --silent --show-error --head -H "Host: ${TENANT_HOST}" "${PROXY}/console/config.json")" "cache-control" "no-cache"

step "the registry refuses an entry path outside the version directory"
TOKEN="$(
  curl --silent --show-error --fail-with-body \
    --request POST "${API}/platform/oauth/token" \
    --user "${FRONTEND_CI_SERVICE_CLIENT_ID:-frontend-ci}:${FRONTEND_CI_SERVICE_CLIENT_SECRET:?set FRONTEND_CI_SERVICE_CLIENT_SECRET}" \
    --data-urlencode "grant_type=client_credentials" \
    --data-urlencode "audience=platform" | jq -er .access_token
)" || fail "the token endpoint refused the frontend-ci client"

refused="$(
  curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --request POST "${API}/platform/api/v1/ui-remotes/${REMOTE}/versions" \
    --header "Authorization: Bearer ${TOKEN}" \
    --header 'Content-Type: application/json' \
    --data "{\"version\":\"${SECOND_VERSION}\",\"entryPath\":\"/console/remotes/somewhere/else/mf-manifest.json\",\"integrity\":\"sha384-$(printf 'A%.0s' {1..64})\",\"sdkMajor\":1}"
)"
[ "${refused}" = "400" ] || fail "the registry answered ${refused} to an outside entry path, not 400"

echo "console smoke passed"
