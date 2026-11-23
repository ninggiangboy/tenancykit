#!/usr/bin/env bash
# Mailroom: templates, the tenant's customers as receivers with attributes, groups and segments, suppressions, one
# transactional email and campaigns in different states. The campaign that is sent really goes through the Mailer to
# Mailpit (http://localhost:8025), so the open pixel, a tracked link and the unsubscribe link can be tried. Sourced by
# seed.sh.

seed_mailroom() {
  step "Mailroom: acme"
  use_app acme mailroom "${PROXY}/mailroom/system/v1"

  # --- templates (Mustache) ---
  local entry key name subject html
  while IFS='|' read -r key name subject html; do
    [ -n "${key}" ] || continue
    acall_try "template ${key}" POST /templates "$(jq -nc --arg k "${key}" --arg n "${name}" --arg s "${subject}" --arg h "${html}" \
      '{key: $k, name: $n, subject: $s, html: $h, text: ($h | gsub("<[^>]*>"; ""))}')" || true
  done <<'TEMPLATES'
welcome|Welcome|Welcome to Acme Coffee, {{receiver.first_name}}|<h1>Welcome, {{receiver.first_name}}!</h1><p>Your first coffee is on us. <a href="https://acme.example.test/welcome">Claim it</a>.</p>
newsletter|Monthly newsletter|{{receiver.first_name}}, what is new in the roastery|<h1>The roastery in November</h1><p>Hi {{receiver.first_name}}, a new single-origin is in. <a href="https://acme.example.test/new-roast">See it</a>.</p>
order-receipt|Order receipt|Your Acme receipt {{order.number}}|<p>Thanks for your order {{order.number}}. Total: {{order.total}}.</p>
TEMPLATES

  # --- receivers: the customers that opted in, with attributes the segments read ---
  local receivers='[
    {"email":"linh.tran@example.test","firstName":"Linh","lastName":"Tran","attributes":{"city":"Ho Chi Minh City","segment":"vip","orders":9}},
    {"email":"minh.nguyen@example.test","firstName":"Minh","lastName":"Nguyen","attributes":{"city":"Ha Noi","segment":"retail","orders":3}},
    {"email":"hoa.pham@example.test","firstName":"Hoa","lastName":"Pham","attributes":{"city":"Da Nang","segment":"retail","orders":1}},
    {"email":"khoa.le@example.test","firstName":"Khoa","lastName":"Le","attributes":{"city":"Ho Chi Minh City","segment":"wholesale","orders":14}},
    {"email":"anh.vo@example.test","firstName":"Anh","lastName":"Vo","attributes":{"city":"Can Tho","segment":"retail","orders":2}},
    {"email":"bao.dang@example.test","firstName":"Bao","lastName":"Dang","attributes":{"city":"Ha Noi","segment":"vip","orders":11}},
    {"email":"chi.bui@example.test","firstName":"Chi","lastName":"Bui","attributes":{"city":"Hai Phong","segment":"retail","orders":1}},
    {"email":"duc.hoang@example.test","firstName":"Duc","lastName":"Hoang","attributes":{"city":"Ho Chi Minh City","segment":"retail","orders":4}},
    {"email":"emma.wilson@example.test","firstName":"Emma","lastName":"Wilson","attributes":{"city":"London","segment":"retail","orders":2}},
    {"email":"lan.truong@example.test","firstName":"Lan","lastName":"Truong","attributes":{"city":"Ho Chi Minh City","segment":"vip","orders":8}},
    {"email":"unsub.person@example.test","firstName":"Una","lastName":"Subscribe","attributes":{"city":"Ha Noi","segment":"retail","orders":1}},
    {"email":"blocked.person@example.test","firstName":"Bo","lastName":"Locked","attributes":{"city":"Ha Noi","segment":"retail","orders":0}}]'
  acall_try "receivers" POST /receivers/bulk "{\"receivers\":${receivers}}" || true

  # --- groups and segments ---
  acall GET "/receivers?limit=100"
  local vip_ids all_ids
  vip_ids=$(printf '%s' "${HTTP_BODY}" | jq -c '[(.data // [])[] | select(.email | test("^(linh|bao|lan)\\."))| .id]')
  all_ids=$(printf '%s' "${HTTP_BODY}" | jq -c '[(.data // [])[] | select(.email | test("^(unsub|blocked)\\.") | not) | .id]')
  VIP_GROUP="" NEWS_GROUP="" HANOI_SEGMENT=""
  mailroom_named /groups "VIP customers" '{"name":"VIP customers"}' VIP_GROUP
  mailroom_named /groups "Newsletter" '{"name":"Newsletter"}' NEWS_GROUP
  [ -z "${VIP_GROUP}" ] || acall_try "vip members" POST "/groups/${VIP_GROUP}/members" "{\"receiverIds\":${vip_ids}}" || true
  [ -z "${NEWS_GROUP}" ] || acall_try "newsletter members" POST "/groups/${NEWS_GROUP}/members" "{\"receiverIds\":${all_ids}}" || true
  mailroom_named /segments "Ha Noi customers" '{"name":"Ha Noi customers","filter":{"field":"city","op":"eq","value":"Ha Noi"}}' HANOI_SEGMENT
  mailroom_named /segments "Big spenders" '{"name":"Big spenders","filter":{"and":[{"field":"orders","op":"gte","value":8},{"field":"segment","op":"in","value":["vip","wholesale"]}]}}' BIG_SEGMENT

  # --- suppressions: one total block, one marketing opt-out ---
  acall_try "suppress blocked" POST /suppressions '{"email":"blocked.person@example.test","scope":"ALL"}' || true
  acall_try "suppress unsub" POST /suppressions '{"email":"unsub.person@example.test","scope":"MARKETING"}' || true

  # --- one transactional email ---
  acall_try "receipt" POST /emails '{"to":"linh.tran@example.test","kind":"TRANSACTIONAL","templateKey":"order-receipt","variables":{"order":{"number":"A-1042","total":"$24.50"}},"idempotencyKey":"seed-acme-receipt"}' "seed-acme-receipt" || true

  # --- campaigns in different states ---
  local sent="" scheduled=""
  mailroom_named /campaigns "Welcome week (sent)" "{\"name\":\"Welcome week (sent)\",\"templateKey\":\"welcome\",\"targetType\":\"GROUP\",\"targetId\":\"${NEWS_GROUP}\"}" sent
  [ -z "${sent}" ] || [ "$(pick '.status')" != "DRAFT" ] || acall_try "send campaign" POST "/campaigns/${sent}/send" || true
  mailroom_named /campaigns "Holiday newsletter (scheduled)" "{\"name\":\"Holiday newsletter (scheduled)\",\"templateKey\":\"newsletter\",\"targetType\":\"SEGMENT\",\"targetId\":\"${BIG_SEGMENT}\"}" scheduled
  if [ -n "${scheduled}" ]; then
    local etag
    etag=$(etag_of "${A_BASE}/campaigns/${scheduled}" "${A_CODE}.localhost" "${A_ADMIN}")
    acall_try "schedule campaign" POST "/campaigns/${scheduled}/schedule" "{\"scheduledAt\":\"$(iso_date +5)T02:00:00Z\"}" "" "${etag}" || true
  fi
  mailroom_named /campaigns "Spring promo (draft)" "{\"name\":\"Spring promo (draft)\",\"templateKey\":\"newsletter\",\"targetType\":\"SEGMENT\",\"targetId\":\"${HANOI_SEGMENT}\"}" draft_unused
}

# A resource addressed by name: found, or created; its id lands in the variable named by the 4th argument.
mailroom_named() {
  local path="$1" name="$2" body="$3" var="$4" id
  acall GET "${path}?limit=100"
  id=$(printf '%s' "${HTTP_BODY}" | jq -r --arg n "${name}" '(.data // []) | map(select(.name == $n))[0].id // empty' 2>/dev/null)
  if [ -z "${id}" ]; then
    acall_try "create ${name}" POST "${path}" "${body}" && id=$(pick '.id')
  else
    # let a caller read the status of what it found
    acall GET "${path}/${id}"
  fi
  printf -v "${var}" '%s' "${id}"
}
