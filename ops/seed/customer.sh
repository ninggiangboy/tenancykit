#!/usr/bin/env bash
# Customer: a tenant's own customers with custom fields, tags and consent; orders over three months with lines and some
# returns; activities of several types; and one erased customer, so the 360 profile, search, segments and the erasure
# case can all be looked at. Sourced by seed.sh.

# ref|name|email|phone|city|segment|tags (comma separated)
CUSTOMERS_acme=(
  "C-001|Linh Tran|linh.tran@example.test|+84 90 100 0001|Ho Chi Minh City|vip|vip,newsletter"
  "C-002|Minh Nguyen|minh.nguyen@example.test|+84 90 100 0002|Ha Noi|retail|newsletter"
  "C-003|Hoa Pham|hoa.pham@example.test|+84 90 100 0003|Da Nang|retail|"
  "C-004|Khoa Le|khoa.le@example.test|+84 90 100 0004|Ho Chi Minh City|wholesale|b2b"
  "C-005|Anh Vo|anh.vo@example.test|+84 90 100 0005|Can Tho|retail|newsletter"
  "C-006|Bao Dang|bao.dang@example.test|+84 90 100 0006|Ha Noi|vip|vip"
  "C-007|Chi Bui|chi.bui@example.test|+84 90 100 0007|Hai Phong|retail|"
  "C-008|Duc Hoang|duc.hoang@example.test|+84 90 100 0008|Ho Chi Minh City|retail|newsletter"
  "C-009|Emma Wilson|emma.wilson@example.test|+44 20 7946 0958|London|retail|tourist"
  "C-010|Farid Khan|farid.khan@example.test|+971 4 123 4567|Dubai|wholesale|b2b,export"
  "C-011|Giang Ngo|giang.ngo@example.test|+84 90 100 0011|Hue|retail|"
  "C-012|Hung Do|hung.do@example.test|+84 90 100 0012|Ha Noi|retail|newsletter"
  "C-013|Isabel Martin|isabel.martin@example.test|+33 1 23 45 67 89|Paris|retail|tourist"
  "C-014|Jun Kato|jun.kato@example.test|+81 3 1234 5678|Tokyo|wholesale|b2b,export"
  "C-015|Kim Le|kim.le@example.test|+84 90 100 0015|Nha Trang|retail|"
  "C-016|Lan Truong|lan.truong@example.test|+84 90 100 0016|Ho Chi Minh City|vip|vip,newsletter"
  "C-017|Mai Phan|mai.phan@example.test|+84 90 100 0017|Da Lat|retail|newsletter"
  "C-018|Nam Ly|nam.ly@example.test|+84 90 100 0018|Ha Noi|retail|"
  "C-019|Oanh Dinh|oanh.dinh@example.test|+84 90 100 0019|Vung Tau|retail|"
  "C-020|Phong Ha|phong.ha@example.test|+84 90 100 0020|Ho Chi Minh City|retail|newsletter"
  "C-021|Quynh Mai|quynh.mai@example.test|+84 90 100 0021|Ha Noi|retail|"
  "C-022|Son Vu|son.vu@example.test|+84 90 100 0022|Can Tho|retail|"
  "C-023|Thao Luu|thao.luu@example.test|+84 90 100 0023|Ho Chi Minh City|retail|newsletter"
  "C-099|Erased Person|erased.person@example.test|+84 90 100 0099|Ha Noi|retail|"
)
CUSTOMERS_globex=(
  "G-001|Bich Ngoc|bich.ngoc@example.test|+84 91 200 0001|Ha Noi|member|gold"
  "G-002|Cuong Tran|cuong.tran@example.test|+84 91 200 0002|Ha Noi|member|"
  "G-003|Dung Pham|dung.pham@example.test|+84 91 200 0003|Hai Phong|trial|trial"
  "G-004|Em Nguyen|em.nguyen@example.test|+84 91 200 0004|Ha Noi|member|gold,newsletter"
  "G-005|Phuc Le|phuc.le@example.test|+84 91 200 0005|Ha Noi|member|"
  "G-006|Giang Vo|giang.vo@example.test|+84 91 200 0006|Bac Ninh|trial|trial"
)

# sku|name|category|unit price (minor units)
PRODUCTS_acme=(
  "SKU-ESP|Espresso beans 1kg|GROCERY|1800"
  "SKU-LAT|Latte, large|BEVERAGE|450"
  "SKU-CRO|Croissant|BAKERY|320"
  "SKU-MUG|Ceramic mug|HOUSEHOLD|1200"
  "SKU-DRP|Drip bag, 10 pack|GROCERY|900"
  "SKU-CAK|Cheesecake slice|BAKERY|520"
  "SKU-GFT|Gift card|GIFT|2500"
)
PRODUCTS_globex=(
  "SKU-CLS|Group class, 10 pack|SERVICE|9000"
  "SKU-PT|Personal trainer session|SERVICE|4500"
  "SKU-SHK|Protein shake|BEVERAGE|600"
  "SKU-TOW|Towel rental|SERVICE|150"
)

seed_customer_tenant() {
  local code="$1" currency="$2" store="$3" people products
  eval "people=(\"\${CUSTOMERS_${code}[@]}\")"
  eval "products=(\"\${PRODUCTS_${code}[@]}\")"
  use_app "${code}" customer "${PROXY}/customer/system/v1"

  # The vocabulary a tenant defines once: custom fields and consent topics.
  local field
  for field in 'city|City|TEXT' 'segment|Segment|CHOICE' 'birthday|Birthday|DATE' 'newsletter_ok|Newsletter OK|BOOLEAN' 'visits|Visits|NUMBER'; do
    IFS='|' read -r key label type <<< "${field}"
    local choices='null'
    [ "${type}" != CHOICE ] || choices='["vip","retail","wholesale","member","trial"]'
    acall_try "field ${key}" POST /custom-fields "$(jq -nc --arg k "${key}" --arg l "${label}" --arg t "${type}" --argjson c "${choices}" \
      '{key: $k, label: $l, type: $t} + (if $c == null then {} else {choices: $c} end)')" || true
  done
  acall_try "topic marketing" POST /consent-topics '{"key":"marketing","label":"Marketing email"}' || true
  acall_try "topic sms" POST /consent-topics '{"key":"sms","label":"SMS offers"}' || true

  local index=0 entry ref name email phone city segment tags tag_json
  for entry in "${people[@]}"; do
    IFS='|' read -r ref name email phone city segment tags <<< "${entry}"
    tag_json=$(printf '%s\n' "${tags}" | jq -Rc 'split(",") | map(select(length > 0))')
    acall_try "customer ${ref}" PUT "/customers/${ref}" "$(jq -nc --arg r "${ref}" --arg n "${name}" --arg e "${email}" --arg p "${phone}" \
      --arg city "${city}" --arg seg "${segment}" --argjson t "${tag_json}" --argjson visits "$((index * 3 % 17 + 1))" \
      '{externalRef: $r, displayName: $n, email: $e, phone: $p, tags: $t,
        customFields: {city: $city, segment: $seg, newsletter_ok: ($t | index("newsletter") != null), visits: $visits}}')" || true

    # Consent: most grant marketing, every fourth withdrew it, every third also grants SMS.
    local status=GRANTED
    [ $((index % 4)) -ne 3 ] || status=REVOKED
    acall_try "consent ${ref}" POST "/customers/${ref}/consents" \
      "{\"topicKey\":\"marketing\",\"status\":\"${status}\",\"occurredAt\":\"$(iso_date "-$((20 + index))")T09:00:00Z\",\"source\":\"seed\"}" || true
    [ $((index % 3)) -ne 0 ] || acall_try "sms consent ${ref}" POST "/customers/${ref}/consents" \
      "{\"topicKey\":\"sms\",\"status\":\"GRANTED\",\"occurredAt\":\"$(iso_date "-$((15 + index))")T09:00:00Z\",\"source\":\"seed\"}" || true

    seed_customer_orders "${code}" "${ref}" "${index}" "${currency}" "${store}" "${products[@]}"
    seed_customer_activities "${ref}" "${index}"
    index=$((index + 1))
  done

  # The erasure case: the person is cleared, the business records stay anonymous.
  [ "${code}" != acme ] || acall_try "erase C-099" POST "/customers/C-099/erasure" || true
  info "customer/${code}: ${index} customers"
}

seed_customer_orders() {
  local code="$1" ref="$2" index="$3" currency="$4" store="$5"
  shift 5
  local products=("$@") count=$(( index % 4 + 1 )) order=0 first second qty total days lines sku name category price
  [ $((index % 6)) -ne 0 ] || count=$((count + 3))   # a few regulars
  while [ "${order}" -lt "${count}" ]; do
    first="${products[$(( (index + order) % ${#products[@]} ))]}"
    second="${products[$(( (index + order + 3) % ${#products[@]} ))]}"
    qty=$(( (index + order) % 3 + 1 ))
    lines="[]"
    total=0
    local line_no=1 entry
    for entry in "${first}" "${second}"; do
      IFS='|' read -r sku name category price <<< "${entry}"
      lines=$(printf '%s' "${lines}" | jq -c --argjson n "${line_no}" --arg sku "${sku}" --arg name "${name}" --arg cat "${category}" \
        --argjson q "${qty}" --argjson amount "$((price * qty))" '. + [{lineNo: $n, sku: $sku, name: $name, category: $cat, quantity: $q, amount: $amount}]')
      total=$((total + price * qty))
      line_no=$((line_no + 1))
    done
    days=$(( 4 + (index * 7 + order * 13) % 85 ))
    local order_ref="ORD-${code}-${ref}-${order}"
    acall_try "order ${order_ref}" PUT "/orders/${order_ref}" "$(jq -nc --arg r "${ref}" --arg cur "${currency}" --arg at "$(iso_date "-${days}")T10:00:00Z" \
      --arg store "${store}" --arg ch "$([ $((order % 2)) -eq 0 ] && echo POS || echo WEB)" --argjson total "${total}" --argjson lines "${lines}" \
      '{externalRef: $r, currency: $cur, occurredAt: $at, storeCode: $store, channel: $ch, total: $total, lines: $lines}')" || true
    # Every seventh order sends back one unit of its first line.
    if [ $(( (index + order) % 7 )) -eq 0 ]; then
      IFS='|' read -r sku name category price <<< "${first}"
      acall_try "return ${order_ref}" PUT "/orders/${order_ref}/returns/RET-${order_ref}" \
        "{\"occurredAt\":\"$(iso_date "-$((days - 2))")T10:00:00Z\",\"lines\":[{\"lineNo\":1,\"quantity\":1,\"amount\":${price}}]}" || true
    fi
    order=$((order + 1))
  done
}

seed_customer_activities() {
  local ref="$1" index="$2" n=0 type
  local types=("app.opened" "email.opened" "support.contacted" "pos.receipt-printed")
  while [ "${n}" -lt $(( index % 3 + 2 )) ]; do
    type="${types[$(( (index + n) % 4 ))]}"
    acall_try "activity ${ref}" POST /activities \
      "{\"externalRef\":\"${ref}\",\"type\":\"${type}\",\"occurredAt\":\"$(iso_date "-$((3 + (index + n * 5) % 40))")T1$((n % 10)):00:00Z\",\"payload\":{\"source\":\"seed\",\"n\":${n}}}" || true
    n=$((n + 1))
  done
}

seed_customer() {
  step "Customer: acme"
  seed_customer_tenant acme USD ST-01
  step "Customer: globex"
  seed_customer_tenant globex VND HN-01
}
