#!/usr/bin/env bash
# Gives every Keycloak user that lacks one the `tenant_id` attribute Keycloak's mail sender needs.
#
# Platform writes `tenant_id` when it creates an identity. Identities created before that carry only the tenant code in
# their username (`<tenant-code>:<email>`), so a password reset for them would fail with "has no tenant_id" until this
# runs. It reads the code-to-id mapping from standard input, one `code,id` per line, so it needs no database access of its
# own; the reserved Platform tenant is added here.
#
#   psql "$PLATFORM_DATABASE_URL" --tuples-only --no-align --field-separator , \
#     --command "select code, id from tenants" | ops/keycloak/backfill-tenant-id.sh
#
# It changes nothing unless APPLY=1. With APPLY=1 it first writes each user's current representation to BACKUP_DIR, so
# the change can be undone by hand from those files, and only then updates the user. Users that already have the
# attribute, and users whose tenant code is not in the mapping, are left alone and reported. Safe to run again.
#
#   KEYCLOAK_URL            default http://localhost:8081
#   KEYCLOAK_REALM          default tenancykit
#   KEYCLOAK_ADMIN_USER     default admin
#   KEYCLOAK_ADMIN_PASSWORD required (the bootstrap administrator's password)
#   BACKUP_DIR              default ops/keycloak/.backup (written only with APPLY=1)
set -euo pipefail

URL="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${KEYCLOAK_REALM:-tenancykit}"
ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:?set the Keycloak administrator password}"
BACKUP_DIR="${BACKUP_DIR:-ops/keycloak/.backup}"
APPLY="${APPLY:-0}"
PAGE=100

# The mapping is a plain `code,id` list, because the bash that ships with macOS has no associative arrays. The reserved
# Platform tenant (RequestScope.PLATFORM_TENANT_ID) is added; its users are named `platform:<email>`.
MAPPING="platform,00000000-0000-7000-8000-000000000001"
while IFS=, read -r code id; do
  if [ -n "${code}" ] && [ -n "${id}" ]; then MAPPING="${MAPPING}"$'\n'"${code},${id}"; fi
done

[ "$(printf '%s\n' "${MAPPING}" | wc -l)" -gt 1 ] || echo "backfill: no tenants on standard input; only platform users can be updated" >&2

tenant_of() { printf '%s\n' "${MAPPING}" | awk -F, -v code="$1" '$1 == code { print $2; exit }'; }

token() {
  curl --silent --show-error --fail-with-body \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=${ADMIN_USER}" --data-urlencode "password=${ADMIN_PASSWORD}" \
    "${URL}/realms/master/protocol/openid-connect/token" | jq -r .access_token
}

TOKEN="$(token)"
api() { # api METHOD PATH [BODY]
  curl --silent --show-error --fail-with-body --request "$1" \
    --header "Authorization: Bearer ${TOKEN}" --header "Content-Type: application/json" \
    ${3:+--data "$3"} "${URL}/admin/realms/${REALM}$2"
}

[ "${APPLY}" = "1" ] && mkdir -p "${BACKUP_DIR}"

updated=0 present=0 unknown=0 unprefixed=0 first=0
while :; do
  page="$(api GET "/users?first=${first}&max=${PAGE}&briefRepresentation=false")"
  count="$(jq length <<<"${page}")"
  [ "${count}" -gt 0 ] || break
  while IFS= read -r user; do
    id="$(jq -r .id <<<"${user}")"
    username="$(jq -r .username <<<"${user}")"
    if [ "$(jq -r '.attributes.tenant_id[0] // empty' <<<"${user}")" != "" ]; then
      present=$((present + 1)); continue
    fi
    case "${username}" in
      *:*) code="${username%%:*}" ;;
      *) unprefixed=$((unprefixed + 1)); echo "skipped ${username}: no tenant code in the username"; continue ;;
    esac
    tenant="$(tenant_of "${code}")"
    if [ -z "${tenant}" ]; then
      unknown=$((unknown + 1)); echo "skipped ${username}: tenant '${code}' is not in the mapping"; continue
    fi
    if [ "${APPLY}" = "1" ]; then
      jq . <<<"${user}" > "${BACKUP_DIR}/${id}.json"
      api PUT "/users/${id}" "$(jq --arg tenant "${tenant}" '.attributes = ((.attributes // {}) + {tenant_id: [$tenant]})' <<<"${user}")" > /dev/null
      echo "updated ${username} -> ${tenant}"
    else
      echo "would update ${username} -> ${tenant}"
    fi
    updated=$((updated + 1))
  done < <(jq -c '.[]' <<<"${page}")
  first=$((first + PAGE))
done

verb="would update"; [ "${APPLY}" = "1" ] && verb="updated"
echo "backfill: ${verb} ${updated}; already had it ${present}; unknown tenant ${unknown}; no tenant in username ${unprefixed}"
[ "${APPLY}" = "1" ] || echo "backfill: nothing was changed; run again with APPLY=1 to write"
