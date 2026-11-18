#!/usr/bin/env bash
# Publishes one UI remote: copies its build to an immutable versioned directory in the console volume and registers the
# version with Platform. Registering never activates anything; an operator activates the version in Platform Admin.
#
#   ops/console/deploy-remote.sh <name> <dist-dir>
#
# The version is the remote package's version plus the short git sha, so the same commit always publishes the same
# version and re-running is a no-op. The plaintext secret of the `frontend-ci` service client comes from the environment
# (FRONTEND_CI_SERVICE_CLIENT_SECRET) and is never printed.
#
# Requirements: node, git, curl, jq, shasum (or openssl), docker compose.
set -euo pipefail

NAME="${1:?usage: deploy-remote.sh <name> <dist-dir>}"
DIST="${2:?usage: deploy-remote.sh <name> <dist-dir>}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_DIR="${REPO_ROOT}/frontend/remotes/${NAME}"
BASE_URL="${CONSOLE_BASE_URL:-http://platform.localhost:8090}"
CLIENT_ID="${FRONTEND_CI_SERVICE_CLIENT_ID:-frontend-ci}"
CLIENT_SECRET="${FRONTEND_CI_SERVICE_CLIENT_SECRET:?set FRONTEND_CI_SERVICE_CLIENT_SECRET in the environment}"

fail() {
  echo "deploy-remote: $*" >&2
  exit 1
}

[ -f "${REMOTE_DIR}/package.json" ] || fail "no remote named ${NAME} under frontend/remotes"
[ -f "${DIST}/mf-manifest.json" ] || fail "${DIST} is not a built remote: it has no mf-manifest.json"
[ -f "${DIST}/console.json" ] || fail "${DIST} is not a built remote: it has no console.json"

VERSION="${REMOTE_VERSION_OVERRIDE:-$(node -p "require('${REMOTE_DIR}/package.json').version")+$(git -C "${REPO_ROOT}" rev-parse --short HEAD)}"
# The build's asset URLs are absolute, so a build made for another version would point outside its own directory.
BUILT_VERSION="$(node -p "JSON.parse(require('node:fs').readFileSync('${DIST}/console.json','utf8')).version")"
[ "${BUILT_VERSION}" = "${VERSION}" ] ||
  fail "${DIST} was built for ${BUILT_VERSION}, not ${VERSION}; build it with REMOTE_VERSION=${VERSION}"
ENTRY_PATH="/console/remotes/${NAME}/${VERSION}/mf-manifest.json"
DIGEST_HEX="$(shasum -a 384 "${DIST}/mf-manifest.json" | cut -d' ' -f1)"
INTEGRITY="sha384-$(openssl dgst -sha384 -binary "${DIST}/mf-manifest.json" | openssl base64 -A)"

ABS_DIST="$(cd "${DIST}" && pwd)"
STATUS="$(
  docker compose --project-directory "${REPO_ROOT}" --profile tools run --rm --no-deps \
    --entrypoint sh -e "EXPECTED=${DIGEST_HEX}" -v "${ABS_DIST}:/dist:ro" console-deploy -c '
      set -eu
      target="/srv/remotes/$0/$1"
      if [ -d "$target" ]; then
        if [ -f "$target/.sha384" ] && [ "$(cat "$target/.sha384")" = "$EXPECTED" ]; then echo SAME; exit 0; fi
        echo "version $1 of $0 is already published with other content; publish a new version" >&2
        exit 1
      fi
      mkdir -p "$(dirname "$target")"
      cp -a /dist "$target"
      printf '%s\n' "$EXPECTED" > "$target/.sha384"
      echo COPIED
    ' "${NAME}" "${VERSION}"
)"

TOKEN="$(
  curl --silent --show-error --fail-with-body \
    --request POST "${BASE_URL}/platform/oauth/token" \
    --user "${CLIENT_ID}:${CLIENT_SECRET}" \
    --data-urlencode "grant_type=client_credentials" \
    --data-urlencode "audience=platform" | jq -er .access_token
)" || fail "the token endpoint refused the frontend-ci client"

PAYLOAD="$(
  node -e '
    const fs = require("fs");
    const pkg = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const built = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
    const declared = pkg.console || {};
    process.stdout.write(JSON.stringify({
      version: process.argv[3],
      entryPath: process.argv[4],
      integrity: process.argv[5],
      sdkMajor: built.sdkMajor,
      appCode: declared.appCode,
      title: declared.title,
      audiences: declared.audiences,
      navOrder: declared.navOrder,
    }));
  ' "${REMOTE_DIR}/package.json" "${DIST}/console.json" "${VERSION}" "${ENTRY_PATH}" "${INTEGRITY}"
)"

HTTP_STATUS="$(
  curl --silent --show-error --output /tmp/deploy-remote-response \
    --write-out '%{http_code}' \
    --request POST "${BASE_URL}/platform/api/v1/ui-remotes/${NAME}/versions" \
    --header "Authorization: Bearer ${TOKEN}" \
    --header 'Content-Type: application/json' \
    --data "${PAYLOAD}"
)"
if [ "${HTTP_STATUS}" != "200" ] && [ "${HTTP_STATUS}" != "201" ]; then
  cat /tmp/deploy-remote-response >&2
  fail "the registry refused the version (HTTP ${HTTP_STATUS})"
fi

case "${STATUS}" in
  SAME) echo "${NAME} ${VERSION} was already published; the registry answered ${HTTP_STATUS}" ;;
  COPIED) echo "${NAME} ${VERSION} published; the registry answered ${HTTP_STATUS}" ;;
  *) fail "unexpected copy result: ${STATUS}" ;;
esac
echo "An operator still has to activate the version in Platform Admin."
