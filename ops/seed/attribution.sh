#!/usr/bin/env bash
# Attribution: campaigns on four channels with different attribution models, tracked links (an A/B split, a capped one,
# a paused one, one about to expire), goals, two weeks of advertising cost, and real traffic: clicks through the public
# redirect from several devices and crawlers, then conversions credited to the clicks that earned them. Traffic is
# produced only on the first run, because clicks cannot be backdated and a second run would double them.
# Sourced by seed.sh.

attribution_click() {
  # usage: attribution_click <slug> <user agent> -> the signed click id on stdout, empty when the redirect did not answer 302
  local location
  location=$(curl --silent --output /dev/null --write-out '%{http_code} %{redirect_url}' "${PROXY}/go/$1" \
    --header "Host: ${A_CODE}.localhost" --header "User-Agent: $2")
  [ "${location%% *}" = 302 ] || return 0
  printf '%s' "${location#* }" | sed -n 's/.*[?&]aid=\([^&]*\).*/\1/p'
}

seed_attribution() {
  step "Attribution: acme"
  use_app acme attribution "${PROXY}/attribution/api/v1"
  acall GET "/campaigns?limit=100"
  local first_run=0
  { [ "$(pick '.data | length')" != "0" ] && [ "${SEED_FORCE_TRAFFIC:-0}" != 1 ]; } || first_run=1

  # --- campaigns: name|slug|channel|model|window days|daily spend (USD, 0 for none) ---
  local campaigns=(
    'Spring email push|spring-email|email|LAST|30|0'
    'Meta Ads - brand video|meta-brand|paid_social|FIRST|14|85.50'
    'Google Ads - search|google-search|paid_search|LINEAR|30|120.00'
    'Creator partnerships|creators|influencer|LINEAR|30|40.00'
  )
  local entry name slug channel model window spend id
  for entry in "${campaigns[@]}"; do
    IFS='|' read -r name slug channel model window spend <<< "${entry}"
    ensure_slug_campaign "${slug}" "$(jq -nc --arg n "${name}" --arg s "${slug}" --arg c "${channel}" --arg m "${model}" --argjson w "${window}" --arg start "$(iso_date -20)" \
      '{name: $n, slug: $s, channel: $c, attributionModel: $m, windowDays: $w, currency: "USD", startsOn: $start}')" || continue
    id="${ENSURED_ID}"
    eval "CAMPAIGN_${slug//-/_}=${id}"
    if [ "${spend}" != 0 ]; then
      local day=14
      while [ "${day}" -ge 1 ]; do
        # cost varies a little from day to day, and a day's clicks and impressions follow it
        local wiggle=$(( (day * 37) % 25 ))
        acall_try "spend ${slug} ${day}" PUT "/campaigns/${id}/spend/$(iso_date "-${day}")" \
          "{\"amount\":\"$(awk -v s="${spend}" -v w="${wiggle}" 'BEGIN{printf "%.2f", s * (0.85 + w / 100)}')\",\"currency\":\"USD\",\"impressions\":$((9000 + wiggle * 410)),\"clicks\":$((260 + wiggle * 9))}" || true
        day=$((day - 1))
      done
    fi
  done

  # --- goals ---
  acall_try "goal purchase" POST /goals '{"key":"purchase","name":"Purchase","defaultValue":"0","currency":"USD"}' || true
  acall_try "goal signup" POST /goals '{"key":"signup","name":"Account signup","defaultValue":"5.00","currency":"USD"}' || true
  acall_try "goal newsletter" POST /goals '{"key":"newsletter","name":"Newsletter subscription","defaultValue":"1.00","currency":"USD"}' || true

  # --- links: slug|campaign slug|utm source|utm medium|destinations JSON ---
  local dest_main='[{"url":"https://acme.example.test/spring","weight":1,"fallback":true}]'
  local ab='[{"url":"https://acme.example.test/lp-a","weight":50,"fallback":true},{"url":"https://acme.example.test/lp-b","weight":50,"fallback":false}]'
  local links=(
    "spring-newsletter|spring-email|newsletter|email|${dest_main}"
    "spring-sms|spring-email|sms|sms|${dest_main}"
    "meta-brand-video|meta-brand|facebook|paid_social|${ab}"
    "google-brand|google-search|google|cpc|${dest_main}"
    "creator-linh|creators|instagram|influencer|${dest_main}"
  )
  local link_slug campaign utm_source utm_medium dests
  for entry in "${links[@]}"; do
    IFS='|' read -r link_slug campaign utm_source utm_medium dests <<< "${entry}"
    eval "id=\${CAMPAIGN_${campaign//-/_}:-}"
    [ -n "${id}" ] || continue
    ensure_slug_link "${link_slug}" "$(jq -nc --arg c "${id}" --arg s "${link_slug}" --arg us "${utm_source}" --arg um "${utm_medium}" --argjson d "${dests}" \
      '{campaignId: $c, slug: $s, utm: {utm_source: $us, utm_medium: $um, utm_campaign: $s}, destinations: $d}')"
  done
  # the cases a link can be in besides healthy: capped, about to expire and paused
  ensure_slug_link "limited-offer" "{\"slug\":\"limited-offer\",\"maxClicks\":5,\"destinations\":${dest_main}}" || true
  ensure_slug_link "flash-ending" "{\"slug\":\"flash-ending\",\"expiresAt\":\"$(iso_days +1)\",\"destinations\":${dest_main}}" || true
  if ensure_slug_link "old-poster" "{\"slug\":\"old-poster\",\"destinations\":${dest_main}}" && [ "${first_run}" = 1 ]; then
    acall_try "pause old-poster" POST "/links/${ENSURED_ID}/pause" || true
  fi

  [ "${first_run}" = 1 ] || { info "attribution traffic already seeded"; return 0; }

  # --- traffic: clicks through the public redirect ---
  local agents=(
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile Safari"
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15"
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
  )
  local slugs=(spring-newsletter spring-sms meta-brand-video google-brand creator-linh meta-brand-video google-brand spring-newsletter)
  local clicks=() n=0 token
  while [ "${n}" -lt 64 ]; do
    link_slug="${slugs[$((n % ${#slugs[@]}))]}"
    token=$(attribution_click "${link_slug}" "${agents[$((n % 4))]}")
    [ -z "${token}" ] || clicks+=("${token}")
    n=$((n + 1))
  done
  # crawlers and link previews are counted apart and earn no credit
  attribution_click spring-newsletter "Googlebot/2.1 (+http://www.google.com/bot.html)" > /dev/null
  attribution_click meta-brand-video "facebookexternalhit/1.1" > /dev/null
  # the capped link spends its five clicks and then answers 410; the paused one answers 404
  n=0
  while [ "${n}" -lt 7 ]; do attribution_click limited-offer "${agents[0]}" > /dev/null; n=$((n + 1)); done
  attribution_click old-poster "${agents[2]}" > /dev/null
  info "attribution: ${#clicks[@]} human clicks recorded"

  # --- conversions: the clicks need a moment to reach the database ---
  sleep 6
  local goals=(purchase purchase purchase signup newsletter purchase) values=(24.50 61.00 129.90 5.00 1.00 18.20) i=0 goal
  while [ "${i}" -lt 14 ] && [ "${i}" -lt "${#clicks[@]}" ]; do
    goal="${goals[$((i % 6))]}"
    acall_try "conversion ${i}" POST /conversions \
      "{\"goalKey\":\"${goal}\",\"clickId\":\"${clicks[$((i * 4))]:-${clicks[0]}}\",\"value\":\"${values[$((i % 6))]}\",\"currency\":\"USD\",\"occurredAt\":\"$(iso_days "-0")\"}" "seed-attribution-${i}" || true
    i=$((i + 1))
  done
  # two with no click to credit: they stay unattributed
  acall_try "unattributed 1" POST /conversions "{\"goalKey\":\"purchase\",\"visitorId\":\"visitor-direct-1\",\"value\":\"42.00\",\"currency\":\"USD\",\"occurredAt\":\"$(iso_days "-0")\"}" "seed-attribution-direct-1" || true
  acall_try "unattributed 2" POST /conversions "{\"goalKey\":\"signup\",\"visitorId\":\"visitor-direct-2\",\"value\":\"5.00\",\"currency\":\"USD\",\"occurredAt\":\"$(iso_days "-0")\"}" "seed-attribution-direct-2" || true
}

# A campaign addressed by its slug: found, or created from the body (id in ENSURED_ID).
ensure_slug_campaign() {
  local slug="$1" body="$2"
  ENSURED_ID=""
  acall GET "/campaigns?limit=100"
  ENSURED_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg s "${slug}" '(.data // []) | map(select(.slug == $s))[0].id // empty' 2>/dev/null)
  [ -z "${ENSURED_ID}" ] || return 0
  acall_try "campaign ${slug}" POST /campaigns "${body}" && ENSURED_ID=$(pick '.id')
  [ -n "${ENSURED_ID}" ]
}

ensure_slug_link() {
  local slug="$1" body="$2"
  ENSURED_ID=""
  acall GET "/links?limit=100"
  ENSURED_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg s "${slug}" '(.data // []) | map(select(.slug == $s))[0].id // empty' 2>/dev/null)
  [ -z "${ENSURED_ID}" ] || return 0
  acall_try "link ${slug}" POST /links "${body}" && ENSURED_ID=$(pick '.id')
  [ -n "${ENSURED_ID}" ]
}
