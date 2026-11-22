#!/usr/bin/env bash
# Platform: the tenants, their apps, their people and the roles those people hold, plus one Tenant System each.
# Sourced by seed.sh after lib.sh.

# Code | name | apps the tenant may use (space separated). The first tenant is the full demo; the rest each exist to make a
# different case reachable: a second company with fewer apps (isolation), an empty one, a deactivated one.
TENANTS=(
  "acme|Acme Coffee Co.|billing notification mailroom survey attribution booking customer loyalty workflow"
  "globex|Globex Fitness|billing notification booking customer loyalty"
  "initech|Initech (empty tenant)|billing customer"
  "dormant|Dormant Ltd (deactivated)|billing"
)

tenant_admin_email() { printf 'admin@%s.localhost' "$1"; }

# A fresh Keycloak token for a tenant's administrator. Access tokens live minutes, so a module asks again rather than
# keeping one from the start of the run.
tenant_token() { kc_token "$1:$(tenant_admin_email "$1")" "${SEED_PASSWORD}"; }
platform_token() { kc_token "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}"; }

# The permission codes of the given apps that a tenant-scope role may hold and that match a jq-like suffix filter.
# usage: codes "<apps>" "<mode>" with mode all | read | none
codes() {
  ruby -ryaml -rjson -e '
    apps, mode = ARGV
    out = apps.split.flat_map { |app|
      YAML.load_file("contracts/permissions/#{app}.yaml")
          .select { |p| p["scopes"].include?("tenant") }
          .map { |p| p["code"] }
    }
    out = out.select { |c| c.end_with?(":read") } if mode == "read"
    puts JSON.generate(out.uniq)
  ' "$1" "$2"
}

ensure_tenant() {
  local code="$1" name="$2"
  req GET "${PROXY}/platform/api/v1/tenants?filter[code][eq]=${code}&limit=1" platform.localhost "${ADMIN_TOKEN}"
  TENANT_ID=$(pick '.data[0].id')
  TENANT_STATUS=$(pick '.data[0].status')
  if [ -n "${TENANT_ID}" ]; then
    info "tenant ${code} exists (${TENANT_STATUS})"
    return 0
  fi
  TENANT_STATUS=ACTIVE
  must "create tenant ${code}" POST "${PROXY}/platform/api/v1/tenants" platform.localhost "${ADMIN_TOKEN}" \
    "{\"code\":\"${code}\",\"name\":\"${name}\",\"adminEmail\":\"$(tenant_admin_email "${code}")\",\"adminTemporaryPassword\":\"Temporary-1\"}"
  TENANT_ID=$(pick '.tenantId')
  info "tenant ${code} created"
}

# A user at a tenant (or at `platform`), loginable with the seed password. Sets USER_ID.
ensure_user() {
  local tenant="$1" email="$2" name="$3" token="$4" host="$5"
  req GET "${PROXY}/platform/api/v1/users?filter[email][eq]=${email}&limit=5" "${host}" "${token}"
  USER_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg t "${tenant}" '(.data // []) | map(select(.email == "'"${email}"'"))[0].id // empty')
  if [ -z "${USER_ID}" ]; then
    must "create user ${email}" POST "${PROXY}/platform/api/v1/users" "${host}" "${token}" \
      "{\"tenantCode\":\"${tenant}\",\"email\":\"${email}\",\"displayName\":\"${name}\",\"temporaryPassword\":\"Temporary-1\"}"
    USER_ID=$(pick '.id')
  fi
  make_loginable "${tenant}:${email}" "${SEED_PASSWORD}"
}

# A role with exactly these permissions, held by the user. Re-running leaves the role as the seed defines it.
ensure_role() {
  local name="$1" permissions="$2" user_id="$3" token="$4" host="$5" role_id version
  req GET "${PROXY}/platform/api/v1/roles?filter[name][eq]=$(printf '%s' "${name}" | jq -sRr @uri)&limit=1" "${host}" "${token}"
  role_id=$(pick '.data[0].id')
  if [ -z "${role_id}" ]; then
    must "create role ${name}" POST "${PROXY}/platform/api/v1/roles" "${host}" "${token}" \
      "{\"name\":\"${name}\",\"permissions\":${permissions}}"
    role_id=$(pick '.id')
  else
    version=$(pick '.data[0].version')
    try "update role ${name}" PUT "${PROXY}/platform/api/v1/roles/${role_id}/permissions" "${host}" "${token}" \
      "{\"permissions\":${permissions}}" "" "\"${version}\"" || true
  fi
  [ -z "${user_id}" ] || try "assign ${name}" POST "${PROXY}/platform/api/v1/roles/${role_id}/users/${user_id}" "${host}" "${token}" || true
}

# Holds a role the system created (its permissions are not the seed's to change).
assign_system_role() {
  local name="$1" user_id="$2" token="$3" host="$4"
  req GET "${PROXY}/platform/api/v1/roles?filter[name][eq]=$(printf '%s' "${name}" | jq -sRr @uri)&limit=1" "${host}" "${token}"
  [ -n "$(pick '.data[0].id')" ] || fail "the system role ${name} does not exist"
  try "assign ${name}" POST "${PROXY}/platform/api/v1/roles/$(pick '.data[0].id')/users/${user_id}" "${host}" "${token}" || true
}

seed_platform() {
  step "Platform: the operator accounts"
  make_loginable "platform:${BOOTSTRAP_EMAIL}" "${BOOTSTRAP_PASSWORD}"
  ADMIN_TOKEN=$(platform_token)

  # The demo's own Platform administrator, so the sign-in below does not depend on what the environment file chose for
  # the bootstrap account.
  ensure_user platform "root@suite.localhost" "Rita Root (platform administrator)" "${ADMIN_TOKEN}" platform.localhost
  assign_system_role "Platform Administrator" "${USER_ID}" "${ADMIN_TOKEN}" platform.localhost

  # A read-only operator: sees tenants and the registry, changes nothing.
  ensure_user platform "support@suite.localhost" "Sam Support (platform, read-only)" "${ADMIN_TOKEN}" platform.localhost
  ensure_role "Platform Support" \
    '["platform:tenant:read","platform:user:read","platform:role:read","platform:ui-remote:read"]' \
    "${USER_ID}" "${ADMIN_TOKEN}" platform.localhost

  local spec code name apps host token
  for spec in "${TENANTS[@]}"; do
    IFS='|' read -r code name apps <<< "${spec}"
    host="${code}.localhost"
    step "Platform: tenant ${code} (${name})"
    ADMIN_TOKEN=$(platform_token)
    ensure_tenant "${code}" "${name}"
    [ "${TENANT_STATUS}" != "DEACTIVATED" ] || continue
    for app in ${apps}; do
      must "enable ${app} for ${code}" PUT "${PROXY}/platform/api/v1/tenants/${TENANT_ID}/apps/${app}" platform.localhost "${ADMIN_TOKEN}" '{"enabled":true}'
    done
    make_loginable "${code}:$(tenant_admin_email "${code}")" "${SEED_PASSWORD}"
    token=$(tenant_token "${code}")
    tenant_ready() { [ "$(code_of GET "${PROXY}/platform/api/v1/tenant-systems" "${host}" "${token}")" = "200" ]; }
    WAIT_SECONDS="${RESTART_SECONDS}" wait_for "tenant ${code} to answer" tenant_ready
    [ "${code}" = "dormant" ] && continue

    # People with different rights, so every console state is one sign-in away.
    ensure_user "${code}" "manager@${code}.localhost" "Maya Manager" "${token}" "${host}"
    ensure_role "Operations Manager" "$(codes "${apps}" all | jq -c 'map(select(test(":(erase|ledger:reverse)") | not))')" "${USER_ID}" "${token}" "${host}"
    ensure_user "${code}" "viewer@${code}.localhost" "Vic Viewer" "${token}" "${host}"
    ensure_role "Read-only" "$(codes "${apps}" read)" "${USER_ID}" "${token}" "${host}"
    ensure_user "${code}" "nobody@${code}.localhost" "Nora NoRole" "${token}" "${host}"
    prepare_system "${code}" "${apps}"
  done

  # The last: the deactivated tenant, after everything else about it exists.
  step "Platform: deactivating dormant"
  ADMIN_TOKEN=$(platform_token)
  req GET "${PROXY}/platform/api/v1/tenants?filter[code][eq]=dormant&limit=1" platform.localhost "${ADMIN_TOKEN}"
  local dormant_id dormant_status dormant_version
  dormant_id=$(pick '.data[0].id')
  dormant_status=$(pick '.data[0].status')
  dormant_version=$(pick '.data[0].version')
  if [ "${dormant_status}" != "DEACTIVATED" ]; then
    try "deactivate dormant" POST "${PROXY}/platform/api/v1/tenants/${dormant_id}/deactivate" platform.localhost "${ADMIN_TOKEN}" "" "" "\"${dormant_version}\"" || true
  fi
}

# ------------------------------ a Tenant System per tenant ------------------------------
# One Tenant System, "demo-backend", stands for the tenant's own software. Its credential's secret is shown once, so the
# first run keeps it in ops/seed/.out/<tenant>.credential.json (ignored by git) and later runs reuse it.

credential_file() { printf '%s/%s.credential.json' "${SEED_OUT}" "$1"; }

# usage: prepare_system <tenant> "<apps>"
prepare_system() {
  local code="$1" apps="$2" host="$1.localhost" file token system_id
  file="$(credential_file "${code}")"
  # A credential kept from an earlier stack no longer works after the volumes were wiped, so it is only reused when
  # Platform still accepts it.
  if [ -s "${file}" ] && system_token_for "${code}" "${apps%% *}" > /dev/null 2>&1; then
    return 0
  fi
  token=$(tenant_token "${code}")
  req GET "${PROXY}/platform/api/v1/tenant-systems?filter[name][eq]=demo-backend&limit=1" "${host}" "${token}"
  system_id=$(pick '.data[0].id')
  if [ -z "${system_id}" ]; then
    must "create tenant system" POST "${PROXY}/platform/api/v1/tenant-systems" "${host}" "${token}" '{"name":"demo-backend"}'
    system_id=$(pick '.id')
  fi
  SMOKE_APPS="${apps}"
  must "issue credential" POST "${PROXY}/platform/api/v1/tenant-systems/${system_id}/credentials" "${host}" "${token}" \
    "{\"label\":\"seed\",\"scopes\":$(tenant_system_scopes)}"
  local key
  key=$(pick '.key')
  jq -n --arg systemId "${system_id}" --arg keyId "$(pick '.credential.keyId')" --arg secret "${key##*_}" \
    '{systemId: $systemId, keyId: $keyId, secret: $secret}' > "${file}"
  chmod 600 "${file}"
  info "tenant system demo-backend of ${code} issued a credential (kept in ${file})"
}

system_id_of() { jq -r .systemId "$(credential_file "$1")"; }

# A suite token for one app, minted from the tenant's credential: what the tenant's own software would send.
system_token_for() {
  local code="$1" audience="$2" file
  file="$(credential_file "${code}")"
  system_token "${audience}" "${code}.localhost" "$(jq -r .keyId "${file}")" "$(jq -r .secret "${file}")"
}

# ------------------------------ console remotes ------------------------------
# `make console` registers each remote's build without activating it, so the console shows nothing until an operator
# activates a version. The demo activates the newest registered version of every remote that has none active.
seed_console_remotes() {
  step "Platform: activating the console remotes"
  ADMIN_TOKEN=$(platform_token)
  req GET "${PROXY}/platform/api/v1/ui-remotes?limit=100" platform.localhost "${ADMIN_TOKEN}"
  local remotes name active version newest
  remotes=$(printf '%s' "${HTTP_BODY}" | jq -c '(.data // [])[]' 2>/dev/null)
  if [ -z "${remotes}" ]; then
    note "no console remote is registered; run \`make console\` and then \`make seed ONLY=platform\`"
    return 0
  fi
  while IFS= read -r remote; do
    name=$(printf '%s' "${remote}" | jq -r .name)
    active=$(printf '%s' "${remote}" | jq -r '.activeVersion // empty')
    newest=$(printf '%s' "${remote}" | jq -r '(.versions // []) | sort_by(.registeredAt) | last | .version // empty')
    version=$(printf '%s' "${remote}" | jq -r .version)
    [ -z "${active}" ] || { info "remote ${name} already runs ${active}"; continue; }
    [ -n "${newest}" ] || { note "remote ${name} has no registered version"; continue; }
    try "activate ${name}" PUT "${PROXY}/platform/api/v1/ui-remotes/${name}" platform.localhost "${ADMIN_TOKEN}" \
      "{\"enabled\":true,\"activeVersion\":\"${newest}\"}" "" "\"${version}\"" && info "remote ${name} now runs ${newest}"
  done <<< "${remotes}"
}
