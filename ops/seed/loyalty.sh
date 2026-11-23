#!/usr/bin/env bash
# Loyalty: a points program with four tiers, promotions (one switched off), rewards (one sold out), and members that
# earn from orders, get points clawed back by returns, spend, redeem rewards into vouchers (issued, used and voided), and
# one member who owes points. Member references are the Customer app's (C-001..), the way a tenant's own software would
# keep the two apps in step. Sourced by seed.sh.

seed_loyalty_program() {
  local etag
  # The program and tiers are the tenant administrator's: `loyalty:program:manage` is not grantable to a Tenant System.
  etag=$(etag_of "${A_BASE}/program" "${A_CODE}.localhost" "${A_ADMIN}")
  acall_admin PUT /program '{"currency":"USD","currencyUnit":100,"expiryMonths":12,"expiryNoticeDays":30,"tierWindowMonths":12,"timeZone":"Asia/Ho_Chi_Minh"}' "" "${etag:-*}"
  ok || [ "${HTTP_CODE}" = 412 ] || note "loyalty/${A_CODE} program: ${HTTP_CODE} ${HTTP_BODY:0:140}"
  etag=$(etag_of "${A_BASE}/tiers" "${A_CODE}.localhost" "${A_ADMIN}")
  acall_admin PUT /tiers '{"tiers":[
    {"code":"BRONZE","name":"Bronze","minSpend":0,"earnMultiplier":1.00},
    {"code":"SILVER","name":"Silver","minSpend":20000,"earnMultiplier":1.25},
    {"code":"GOLD","name":"Gold","minSpend":60000,"earnMultiplier":1.50},
    {"code":"PLATINUM","name":"Platinum","minSpend":150000,"earnMultiplier":2.00}]}' "" "${etag:-*}"
  ok || [ "${HTTP_CODE}" = 412 ] || note "loyalty/${A_CODE} tiers: ${HTTP_CODE} ${HTTP_BODY:0:140}"
}

seed_loyalty_catalog() {
  # name|json
  local promotions=(
    'Double groceries|{"name":"Double groceries","multiplier":2.00,"priority":1,"categories":["GROCERY"]}'
    'Weekend bakery|{"name":"Weekend bakery","multiplier":1.50,"priority":2,"categories":["BAKERY"],"daysOfWeek":[6,7]}'
    'Flash week (ended)|{"name":"Flash week (ended)","multiplier":3.00,"priority":3,"active":false}'
  )
  local entry name body existing
  acall GET "/promotions?limit=100"
  existing=$(printf '%s' "${HTTP_BODY}" | jq -r '(.data // .) | map(.name) | .[]' 2>/dev/null)
  for entry in "${promotions[@]}"; do
    name="${entry%%|*}"
    body="${entry#*|}"
    printf '%s\n' "${existing}" | grep -qxF "${name}" && continue
    acall_admin POST /promotions "${body}"
    ok || note "loyalty/${A_CODE} promotion ${name}: ${HTTP_CODE} ${HTTP_BODY:0:120}"
  done
  # name|cost|stock|voucherValidDays
  local rewards=('Free coffee|50|200|30' 'Ceramic mug|120|20|30' 'Gift card $5|400|50|90' 'Limited roast (sold out)|80|0|14')
  acall GET "/rewards?limit=100"
  existing=$(printf '%s' "${HTTP_BODY}" | jq -r '(.data // .) | map(.name) | .[]' 2>/dev/null)
  for entry in "${rewards[@]}"; do
    IFS='|' read -r name cost stock days <<< "${entry}"
    printf '%s\n' "${existing}" | grep -qxF "${name}" && continue
    acall_admin POST /rewards "{\"name\":\"${name}\",\"pointsCost\":${cost},\"stock\":${stock},\"voucherValidDays\":${days}}"
    ok || note "loyalty/${A_CODE} reward ${name}: ${HTTP_CODE} ${HTTP_BODY:0:120}"
  done
}

reward_id() {
  acall GET "/rewards?limit=100"
  printf '%s' "${HTTP_BODY}" | jq -r --arg n "$1" '(.data // .) | map(select(.name == $n))[0] | (.rewardId // .id) // empty'
}

# The amount of one seeded order, so the debt case below can return exactly what an order earned.
loyalty_order_amount() {
  local index="$1" order="$2" scale=1
  [ $((index % 5)) -ne 0 ] || scale=6
  printf '%s' $(( (2000 + (index * 7919 + order * 1543) % 9000) * scale ))
}

seed_loyalty_members() {
  local index=0 ref amount day order_ref order_count order coffee mug
  coffee=$(reward_id "Free coffee")
  mug=$(reward_id "Ceramic mug")
  while [ "${index}" -lt 16 ]; do
    ref=$(printf 'C-%03d' $((index + 1)))
    acall_try "member ${ref}" PUT "/members/${ref}" "{\"displayName\":\"member ${ref}\"}" || true
    order_count=$(( index % 4 + 2 ))
    order=0
    while [ "${order}" -lt "${order_count}" ]; do
      amount=$(loyalty_order_amount "${index}" "${order}")
      day=$(( 4 + (index * 7 + order * 13) % 85 ))
      order_ref="LORD-${A_CODE}-${ref}-${order}"
      acall_try "order ${order_ref}" PUT "/orders/${order_ref}" \
        "{\"externalRef\":\"${ref}\",\"currency\":\"USD\",\"occurredAt\":\"$(iso_date "-${day}")T10:00:00Z\",\"storeCode\":\"ST-01\",\"channel\":\"POS\",
          \"lines\":[{\"lineNo\":1,\"sku\":\"SKU-ESP\",\"category\":\"GROCERY\",\"quantity\":1,\"amount\":$((amount * 6 / 10))},
                     {\"lineNo\":2,\"sku\":\"SKU-CRO\",\"category\":\"BAKERY\",\"quantity\":2,\"amount\":$((amount * 4 / 10))}]}" || true
      # A return claws back part of the points of every third order.
      [ $(( (index + order) % 3 )) -ne 0 ] || acall_try "return ${order_ref}" PUT "/orders/${order_ref}/returns/LRET-${order_ref}" \
        "{\"occurredAt\":\"$(iso_date "-$((day - 1))")T10:00:00Z\",\"lines\":[{\"lineNo\":2,\"quantity\":1,\"amount\":$((amount * 2 / 10))}]}" || true
      order=$((order + 1))
    done

    # Spending: a plain redemption for some, a reward (and its voucher) for others.
    [ $((index % 4)) -ne 1 ] || acall_try "spend ${ref}" POST "/members/${ref}/redemptions" "{\"points\":20,\"reference\":\"POS-${ref}\"}" "seed-spend-${A_CODE}-${ref}" || true
    if [ $((index % 3)) -eq 0 ] && [ -n "${coffee}" ] && acall_try "reward ${ref}" POST "/members/${ref}/reward-redemptions" "{\"rewardId\":\"${coffee}\"}" "seed-reward-${A_CODE}-${ref}"; then
      local code redemption
      code=$(pick '.voucher.code')
      redemption=$(pick '.redemptionId')
      # every second voucher is used; the one of the first member is returned unused, which voids it
      if [ $((index % 6)) -eq 0 ] && [ -n "${code}" ]; then
        acall_try "use voucher ${ref}" POST "/vouchers/${code}/use" "{\"useRef\":\"USE-${ref}\"}" || true
      elif [ "${index}" -eq 3 ] && [ -n "${redemption}" ]; then
        acall_try "reverse ${ref}" POST "/members/${ref}/redemptions/${redemption}/reversal" || true
      fi
    fi
    [ $((index % 5)) -ne 2 ] || [ -z "${mug}" ] || acall_try "mug ${ref}" POST "/members/${ref}/reward-redemptions" "{\"rewardId\":\"${mug}\"}" "seed-mug-${A_CODE}-${ref}" || true
    index=$((index + 1))
  done

  # A member in debt: a return after everything was spent, so the next earn repays first.
  local debtor="C-008" available
  acall GET "/members/${debtor}"
  available=$(pick '.available')
  if [ -n "${available}" ] && [ "${available}" -gt 0 ]; then
    acall_try "spend all ${debtor}" POST "/members/${debtor}/redemptions" "{\"points\":${available},\"reference\":\"POS-ALL-${debtor}\"}" "seed-spend-all-${A_CODE}" || true
    acall_try "return ${debtor}" PUT "/orders/LORD-${A_CODE}-${debtor}-0/returns/LRET-DEBT-${debtor}" \
      "{\"occurredAt\":\"$(iso_date -1)T10:00:00Z\",\"lines\":[{\"lineNo\":1,\"quantity\":1,\"amount\":$(( $(loyalty_order_amount 7 0) * 6 / 10 ))}]}" || true
  fi
  # The erasure case.
  acall_try "erase C-016" POST "/members/C-016/erasure" || true
}

seed_loyalty() {
  step "Loyalty: acme"
  use_app acme loyalty "${PROXY}/loyalty/system/v1"
  seed_loyalty_program
  seed_loyalty_catalog
  seed_loyalty_members
}
