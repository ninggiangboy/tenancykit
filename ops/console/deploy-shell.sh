#!/usr/bin/env bash
# Stages a shell build into the console volume: shells/platform or shells/tenant. The swap is a rename, so a reload
# never sees half a build.
#
#   ops/console/deploy-shell.sh <platform|tenant> <dist-dir>
#
# Requirements: docker compose.
set -euo pipefail

NAME="${1:?usage: deploy-shell.sh <platform|tenant> <dist-dir>}"
DIST="${2:?usage: deploy-shell.sh <platform|tenant> <dist-dir>}"

case "${NAME}" in
  platform|tenant) ;;
  *) echo "deploy-shell: the shell is platform or tenant, not ${NAME}" >&2; exit 2 ;;
esac

[ -f "${DIST}/index.html" ] || {
  echo "deploy-shell: ${DIST} is not a built shell: it has no index.html" >&2
  exit 1
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ABS_DIST="$(cd "${DIST}" && pwd)"

docker compose --project-directory "${REPO_ROOT}" --profile tools run --rm --no-deps \
  --entrypoint sh -v "${ABS_DIST}:/dist:ro" console-deploy -c '
    set -eu
    mkdir -p /srv/shells
    rm -rf "/srv/shells/$0.next"
    cp -a /dist "/srv/shells/$0.next"
    rm -rf "/srv/shells/$0"
    mv "/srv/shells/$0.next" "/srv/shells/$0"
  ' "${NAME}"

echo "${NAME} shell staged"
