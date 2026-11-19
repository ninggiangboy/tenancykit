#!/usr/bin/env bash
# Writes a ready-to-use .env for the local compose stack from .env.example: every placeholder becomes a fresh random
# value, and every service-client hash is the SHA-256 of the secret it must match. It never overwrites an existing
# .env; remove that file yourself (or pass FORCE=1) to start over. When a .env exists it only gains the variables that
# .env.example has and it lacks (for a service added since it was written), each with a fresh value; every value
# already in it is kept.
#
#   ops/env/generate-env.sh                       # writes .env at the repository root
#   ENV_OUTPUT=/some/path ops/env/generate-env.sh # writes elsewhere
#   PLATFORM_ADMIN_EMAIL=me@example.com PLATFORM_ADMIN_PASSWORD='Temporary-1' ops/env/generate-env.sh
#
# Values are for local development only. Anything left blank in the example (the sample tenant's Platform credential,
# the token signing key) stays blank: Platform creates the first on request and generates an ephemeral key for the
# second. A database volume created with an earlier .env keeps its old passwords; run `make clean` first.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="${ROOT}/.env.example"
TARGET="${ENV_OUTPUT:-${ROOT}/.env}"

ADMIN_EMAIL="${PLATFORM_ADMIN_EMAIL:-admin@suite.localhost}"
ADMIN_PASSWORD="${PLATFORM_ADMIN_PASSWORD:-Temporary-1}"

[ -f "${EXAMPLE}" ] || { echo "generate-env: ${EXAMPLE} is missing" >&2; exit 1; }
MERGE=0
if [ -e "${TARGET}" ] && [ "${FORCE:-0}" != "1" ]; then MERGE=1; fi

random_hex() { openssl rand -hex 24; }
random_key() { openssl rand -base64 32; }
sha256() { printf %s "$1" | shasum -a 256 | cut -d' ' -f1; }

# Service-client hash -> the secret variable whose SHA-256 it stores.
hash_source() {
  case "$1" in
    PLATFORM_SERVICE_CLIENT_SECRET_HASH) echo BILLING_REPLICA_CLIENT_SECRET ;;
    PLATFORM_FRONTEND_CI_SERVICE_CLIENT_SECRET_HASH) echo FRONTEND_CI_SERVICE_CLIENT_SECRET ;;
    PLATFORM_*_SERVICE_CLIENT_SECRET_HASH)
      name="${1#PLATFORM_}"
      echo "${name%_SERVICE_CLIENT_SECRET_HASH}_REPLICA_CLIENT_SECRET" ;;
  esac
}

# Pass 1: choose a value for every placeholder, keeping the example's order and comments.
# Chosen values live in V_<key> variables: macOS ships bash 3.2, which has no associative arrays.
lines=()
while IFS= read -r line || [ -n "${line}" ]; do
  lines+=("${line}")
  case "${line}" in
    ''|'#'*) continue ;;
  esac
  key="${line%%=*}"
  current="${line#*=}"
  case "${key}" in
    PLATFORM_BOOTSTRAP_ADMIN_EMAIL) printf -v "V_${key}" %s "${ADMIN_EMAIL}" ;;
    PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD) printf -v "V_${key}" %s "${ADMIN_PASSWORD}" ;;
    *_SECRET_HASH) ;;  # pass 2
    *_MASTER_KEY|WORKFLOW_SECRET_KEY)
      [ "${current}" = "replace-me" ] && printf -v "V_${key}" %s "$(random_key)" ;;
    *)
      case "${current}" in replace-me|replace-me-too) printf -v "V_${key}" %s "$(random_hex)" ;; esac ;;
  esac
done < "${EXAMPLE}"

# Pass 2: hashes follow the secrets chosen above.
for line in "${lines[@]}"; do
  case "${line}" in
    ''|'#'*) continue ;;
  esac
  key="${line%%=*}"
  case "${key}" in
    *_SECRET_HASH)
      source_key="$(hash_source "${key}")"
      secret_var="V_${source_key}"
      [ -n "${source_key}" ] && [ -n "${!secret_var:-}" ] ||
        { echo "generate-env: no secret found for ${key}" >&2; exit 1; }
      printf -v "V_${key}" %s "$(sha256 "${!secret_var}")" ;;
  esac
done

# Merge: append only what the existing file lacks, keeping the example's order and comments for those lines.
if [ "${MERGE}" = "1" ]; then
  missing=0
  for line in "${lines[@]}"; do
    case "${line}" in ''|'#'*) continue ;; esac
    key="${line%%=*}"
    grep -q "^${key}=" "${TARGET}" && continue
    if [ "${missing}" = "0" ]; then printf '\n# Added by ops/env/generate-env.sh: present in .env.example, missing here.\n' >> "${TARGET}"; fi
    missing=$((missing + 1))
    chosen="V_${key}"
    if [ -n "${!chosen+set}" ]; then printf '%s=%s\n' "${key}" "${!chosen}" >> "${TARGET}"; else printf '%s\n' "${line}" >> "${TARGET}"; fi
  done
  echo "generate-env: ${TARGET} kept as is; added ${missing} missing variable(s)" >&2
  exit 0
fi

# Write: same lines as the example with the chosen values substituted.
umask 077
tmp="$(mktemp "${TARGET}.XXXXXX")"
trap 'rm -f "${tmp}"' EXIT
for line in "${lines[@]}"; do
  case "${line}" in
    ''|'#'*) printf '%s\n' "${line}" ;;
    *)
      key="${line%%=*}"
      chosen="V_${key}"
      if [ -n "${!chosen+set}" ]; then printf '%s=%s\n' "${key}" "${!chosen}"; else printf '%s\n' "${line}"; fi ;;
  esac
done > "${tmp}"
mv "${tmp}" "${TARGET}"
trap - EXIT

echo "generate-env: wrote ${TARGET}"
echo "  platform admin: ${ADMIN_EMAIL} / ${ADMIN_PASSWORD} (temporary; Keycloak asks for a new one on first sign-in)"
