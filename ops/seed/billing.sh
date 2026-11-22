#!/usr/bin/env bash
# Billing: customers with wallets in four currencies, charges and invoices in every state, pricing plans of every model,
# subscriptions, coupons and redemptions, reservations, usage events and webhook endpoints.
#
# Payments and refunds are not seeded: Billing creates them only from Stripe's answers and the local stack has no Stripe
# account, so those two screens stay empty by design.
# Sourced by seed.sh after lib.sh and platform.sh.

# The calls below use acall/acall_try (lib.sh) as the tenant's Tenant System, see use_app.

# name|email name|currency|mode|creditLimit (minor units)|charge amounts (minor units, comma separated)
PEOPLE_acme=(
  "Lumen Coffee|lumen.coffee|USD|POSTPAID|5000000|12500,8640,32000"
  "Northwind Labs|northwind.labs|USD|POSTPAID|5000000|4120,150000,9900"
  "Bánh Mì 37|banh.mi.37|VND|POSTPAID|500000000|3200000,18500000,450000"
  "Orbit Dental|orbit.dental|USD|PREPAID|0|"
  "Kestrel Freight|kestrel.freight|EUR|POSTPAID|2000000|5325,129900"
  "Fieldnote Studio|fieldnote.studio|GBP|PREPAID|0|"
  "Harbor Books|harbor.books|USD|POSTPAID|100000|1299"
)
PEOPLE_globex=(
  "Iron Gym Hanoi|iron.gym.hanoi|VND|POSTPAID|200000000|1500000,350000"
  "Pulse Studio|pulse.studio|USD|PREPAID|0|"
  "Yoga Loft|yoga.loft|USD|POSTPAID|500000|4900,12000"
)

# Creates the customer (replaying safely) and answers its id in CUSTOMER_ID.
billing_customer() {
  local index="$1" name="$2" email="$3@${A_CODE}.test" system_id="$4" ref
  ref="seed-${A_CODE}-${index}"
  acall POST /customers "{\"tenantSystemId\":\"${system_id}\",\"externalUserId\":\"${ref}\",\"displayName\":\"${name}\",\"email\":\"${email}\"}" "seed-customer-${A_CODE}-${index}"
  CUSTOMER_ID=$(pick '.id')
  if [ -z "${CUSTOMER_ID}" ]; then
    # The idempotency window passed on a later run: find the customer the earlier run made.
    acall GET "/customers?limit=100"
    CUSTOMER_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg ref "${ref}" '(.data // []) | map(select(.externalUserId == $ref))[0].id // empty')
  fi
  [ -n "${CUSTOMER_ID}" ] || fail "billing/${A_CODE}: could not create customer ${name}: ${HTTP_CODE} ${HTTP_BODY:0:200}"
}

seed_billing_people() {
  local people_var="PEOPLE_${A_CODE}[@]" system_id entry name slug currency mode limit charges index=0 n amount
  eval "local people=(\"\${${people_var}}\")"
  system_id=$(system_id_of "${A_CODE}")
  WALLET_IDS=()
  CUSTOMER_IDS=()
  WALLET_CURRENCY=()
  for entry in "${people[@]}"; do
    IFS='|' read -r name slug currency mode limit charges <<< "${entry}"
    billing_customer "${index}" "${name}" "${slug}" "${system_id}"
    CUSTOMER_IDS[index]="${CUSTOMER_ID}"
    acall POST /wallets "{\"customerId\":\"${CUSTOMER_ID}\",\"currency\":\"${currency}\",\"mode\":\"${mode}\",\"creditLimit\":${limit}}" "seed-wallet-${A_CODE}-${index}"
    if ! ok; then
      acall GET "/wallets?limit=100"
      WALLET_IDS[index]=$(printf '%s' "${HTTP_BODY}" | jq -r --arg c "${CUSTOMER_ID}" --arg cur "${currency}" '(.data // []) | map(select(.customerId == $c and .currency == $cur))[0].id // empty')
    else
      WALLET_IDS[index]=$(pick '.id')
    fi
    [ -n "${WALLET_IDS[index]}" ] || fail "billing/${A_CODE}: no wallet for ${name}: ${HTTP_CODE} ${HTTP_BODY:0:200}"
    WALLET_CURRENCY[index]="${currency}"

    # Each charge gets an invoice: the first stays open, the second is voided, the third is written off.
    n=0
    for amount in ${charges//,/ }; do
      acall_try "charge ${index}/${n}" POST /charges \
        "{\"walletId\":\"${WALLET_IDS[index]}\",\"amount\":${amount},\"currency\":\"${currency}\",\"reference\":\"seed-${A_CODE}-${index}-${n}\"}" \
        "seed-charge-${A_CODE}-${index}-${n}" || { n=$((n + 1)); continue; }
      local charge_id invoice_id
      charge_id=$(pick '.id')
      if acall_try "invoice ${index}/${n}" POST /invoices "{\"chargeId\":\"${charge_id}\"}"; then
        invoice_id=$(pick '.invoice.id')
        if [ -n "${invoice_id}" ] && [ "${HTTP_CODE}" = 201 ]; then
          [ "${n}" != 1 ] || acall_try "void ${index}/${n}" POST "/invoices/${invoice_id}/void" '{"reason":"Billed twice"}' || true
          [ "${n}" != 2 ] || acall_try "write off ${index}/${n}" POST "/invoices/${invoice_id}/uncollectible" '{"reason":"Customer unreachable"}' || true
        fi
      fi
      n=$((n + 1))
    done
    index=$((index + 1))
  done
}

seed_billing_catalog() {
  local usage='"metric":"api_calls","unit":"call","unitPrice":"0.002"'
  # code|name|currency|model|cycle|rule (JSON without the model)
  local plans=(
    'starter|Starter|USD|SUBSCRIPTION|MONTHLY|{"fixedPrice":"29.00"}'
    'growth|Growth|USD|SUBSCRIPTION|YEARLY|{"fixedPrice":"990.00"}'
    "api-calls|API calls|USD|USAGE_BASED|MONTHLY|{${usage}}"
    'compute-time|Compute time|USD|TIME_BASED|MONTHLY|{"metric":"compute_seconds","unit":"second","unitPrice":"0.0005","increment":"60"}'
    "platform-hybrid|Platform and usage|USD|HYBRID|MONTHLY|{\"fixedPrice\":\"99.00\",\"components\":[{\"model\":\"USAGE_BASED\",${usage}}]}"
    'storage|Storage|USD|USAGE_BASED|MONTHLY|{"metric":"storage_gb","unit":"gb","unitPrice":"0.10"}'
    'batch-jobs|Batch jobs|VND|WORKLOAD_BASED|MONTHLY|{"metric":"jobs","unit":"job","unitPrice":"1500","executionFee":"200"}'
  )
  local entry code name currency model cycle rule
  PLAN_IDS_starter="" PLAN_IDS_growth="" PLAN_IDS_hybrid="" PLAN_IDS_batch=""
  acall GET "/pricing-plans?limit=100"
  local existing="${HTTP_BODY}"
  for entry in "${plans[@]}"; do
    IFS='|' read -r code name currency model cycle rule <<< "${entry}"
    local id
    id=$(printf '%s' "${existing}" | jq -r --arg c "${code}" '(.data // []) | map(select(.code == $c))[0].id // empty')
    if [ -z "${id}" ]; then
      if acall_try "plan ${code}" POST /pricing-plans \
        "$(jq -nc --arg code "${code}" --arg name "${name}" --arg cur "${currency}" --arg model "${model}" --arg cycle "${cycle}" --argjson rule "${rule}" \
          '{code: $code, name: $name, currency: $cur, model: $model, version: {billingCycle: $cycle, rule: ({model: $model} + $rule)}}')"; then
        id=$(pick '.id')
        # one plan with a second version, one retired
        [ "${code}" != starter ] || acall_try "starter v2" POST "/pricing-plans/${id}/versions" '{"billingCycle":"MONTHLY","rule":{"model":"SUBSCRIPTION","fixedPrice":"39.00"}}' || true
        [ "${code}" != batch-jobs ] || acall_try "retire batch-jobs" POST "/pricing-plans/${id}/retire" || true
      fi
    fi
    case "${code}" in
      starter) PLAN_IDS_starter="${id}" ;;
      growth) PLAN_IDS_growth="${id}" ;;
      platform-hybrid) PLAN_IDS_hybrid="${id}" ;;
    esac
  done
}

seed_billing_subscriptions() {
  local sub_id
  # Lumen on Starter, Northwind on Growth (then canceled), Orbit Dental on the hybrid plan.
  [ -z "${PLAN_IDS_starter}" ] || acall_try "subscribe starter" POST /subscriptions "{\"walletId\":\"${WALLET_IDS[0]}\",\"planId\":\"${PLAN_IDS_starter}\"}" "seed-sub-${A_CODE}-starter" || true
  if [ -n "${PLAN_IDS_growth}" ] && acall_try "subscribe growth" POST /subscriptions "{\"walletId\":\"${WALLET_IDS[1]}\",\"planId\":\"${PLAN_IDS_growth}\"}" "seed-sub-${A_CODE}-growth"; then
    sub_id=$(pick '.subscription.id')
    [ -z "${sub_id}" ] || acall_try "cancel growth" POST "/subscriptions/${sub_id}/cancel" || true
  fi
  [ -z "${PLAN_IDS_hybrid}" ] || acall_try "subscribe hybrid" POST /subscriptions "{\"walletId\":\"${WALLET_IDS[3]}\",\"planId\":\"${PLAN_IDS_hybrid}\"}" "seed-sub-${A_CODE}-hybrid" || true
}

seed_billing_coupons() {
  # code|name|benefit JSON|limits JSON|stackable
  local coupons=(
    'WELCOME10|Welcome 10%|{"type":"PERCENT_OFF","basisPoints":1000}|{"maxRedemptions":100}|false'
    'SPRING5|Spring $5 off|{"type":"AMOUNT_OFF","amount":500}|{"maxRedemptions":200,"maxPerCustomer":1}|false'
    'CREDIT20|Wallet credit $20|{"type":"WALLET_CREDIT","amount":2000}|{"maxRedemptions":500}|true'
    'BF2026|Black Friday|{"type":"PERCENT_OFF","basisPoints":3000}|{"maxRedemptions":1000}|false'
  )
  local entry code name benefit limits stackable welcome_id spring_id bf_id id redeemed
  for entry in "${coupons[@]}"; do
    IFS='|' read -r code name benefit limits stackable <<< "${entry}"
    id=""
    if acall_try "coupon ${code}" POST /coupons \
      "$(jq -nc --arg code "${code}" --arg name "${name}" --argjson benefit "${benefit}" --argjson limits "${limits}" --argjson stack "${stackable}" \
        '{name: $name, currency: "USD", benefit: $benefit, limits: $limits, stackable: $stack, code: $code, codeMaxRedemptions: 50}')"; then
      id=$(pick '.coupon.id')
    fi
    case "${code}" in WELCOME10) welcome_id="${id}" ;; SPRING5) spring_id="${id}" ;; BF2026) bf_id="${id}" ;; esac
  done
  [ -z "${welcome_id}" ] || acall_try "extra code" POST "/coupons/${welcome_id}/codes" '{"code":"WELCOME10-VIP","maxRedemptions":5}' || true
  [ -z "${bf_id}" ] || acall_try "deactivate BF2026" POST "/coupons/${bf_id}/deactivate" '{"revokeRedemptions":false}' || true
  # One redemption in force, one revoked.
  [ -z "${welcome_id}" ] || acall_try "redeem WELCOME10" POST "/wallets/${WALLET_IDS[0]}/coupon-redemptions/attach" "{\"couponId\":\"${welcome_id}\"}" "seed-redeem-${A_CODE}-1" || true
  if [ -n "${spring_id}" ] && acall_try "redeem SPRING5" POST "/wallets/${WALLET_IDS[1]}/coupon-redemptions/attach" "{\"couponId\":\"${spring_id}\"}" "seed-redeem-${A_CODE}-2"; then
    redeemed=$(pick '.id')
    [ -z "${redeemed}" ] || acall_try "revoke redemption" POST "/coupon-redemptions/${redeemed}/revoke" || true
  fi
}

seed_billing_reservations() {
  local wallet="${WALLET_IDS[0]}" capture release
  hold() { # amount reference ttl wallet
    acall_try "reserve $2" POST /reservations \
      "{\"walletId\":\"${4:-${wallet}}\",\"amount\":$1,\"currency\":\"USD\",\"ttlSeconds\":${3:-3600},\"reference\":\"$2\"}" "seed-${A_CODE}-$2" && HELD_ID=$(pick '.id')
  }
  if hold 5000 capture; then
    capture="${HELD_ID}"
    acall_try "capture" POST "/reservations/${capture}/capture" '{"amount":4500,"currency":"USD","reference":"capture"}' "seed-${A_CODE}-capture-now" || true
  fi
  if hold 3000 release; then
    release="${HELD_ID}"
    acall_try "release" POST "/reservations/${release}/release" || true
  fi
  hold 8000 held || true
  hold 2000 held-2 7200 "${WALLET_IDS[1]}" || true
}

seed_billing_usage() {
  local events="[]" index day entry metric unit base
  local metrics=("api_calls|call|1200" "storage_gb|gb|40" "compute_seconds|second|900")
  for index in 0 1 2; do
    for day in 0 1 2 3 4 5 6 7; do
      entry="${metrics[$((day % 3))]}"
      IFS='|' read -r metric unit base <<< "${entry}"
      events=$(printf '%s' "${events}" | jq -c --arg c "${CUSTOMER_IDS[index]}" --arg m "${metric}" --arg u "${unit}" \
        --arg q "$((base + day * 37))" --arg at "$(iso_date "-$((day * 2 + 1))")T09:00:00Z" --arg k "seed-${A_CODE}-${CUSTOMER_IDS[index]}-${day}" \
        '. + [{customerId: $c, metric: $m, unit: $u, quantity: $q, occurredAt: $at, idempotencyKey: $k}]')
    done
  done
  acall_try "usage events" POST /usage-events/batch "{\"events\":${events}}" || true
}

seed_billing_webhooks() {
  local name types
  for entry in \
    'finance|["invoice.created","invoice.paid","payment.succeeded","payment.failed"]' \
    'alerts|["wallet.debited","reservation.expired","charge.failed"]' \
    'audit|["customer.created","pricing-plan.version-published","pricing-plan.retired"]' \
    'marketing|["coupon.redeemed","coupon.redemption-ended"]'; do
    name="${entry%%|*}"
    types="${entry#*|}"
    acall GET "/webhook-endpoints?limit=100"
    printf '%s' "${HTTP_BODY}" | jq -e --arg u "https://${A_CODE}.example.test/hooks/${name}" '(.data // []) | any(.url == $u)' > /dev/null 2>&1 && continue
    acall_try "webhook ${name}" POST /webhook-endpoints \
      "$(jq -nc --arg u "https://${A_CODE}.example.test/hooks/${name}" --arg d "$(printf '%s' "${name:0:1}" | tr '[:lower:]' '[:upper:]')${name:1} feed" --argjson t "${types}" \
        '{url: $u, description: $d, eventTypes: $t}')" || true
  done
}

seed_billing() {
  local code
  for code in acme globex; do
    step "Billing: ${code}"
    use_app "${code}" billing "${PROXY}/api/v1"
    seed_billing_people
    # The customer who left: the last one of the first tenant.
    if [ "${code}" = acme ]; then
      acall_try "deactivate Harbor Books" POST "/customers/${CUSTOMER_IDS[6]}/deactivate" || true
      seed_billing_catalog
      seed_billing_subscriptions
      seed_billing_coupons
      seed_billing_reservations
      seed_billing_usage
      seed_billing_webhooks
    else
      # A smaller second tenant: enough to see it differs from the first and that neither sees the other.
      acall_try "plan pass" POST /pricing-plans '{"code":"gym-pass","name":"Monthly pass","currency":"VND","model":"SUBSCRIPTION","version":{"billingCycle":"MONTHLY","rule":{"model":"SUBSCRIPTION","fixedPrice":"650000"}}}' || true
      acall_try "coupon" POST /coupons '{"name":"Member 15%","currency":"VND","benefit":{"type":"PERCENT_OFF","basisPoints":1500},"limits":{"maxRedemptions":50},"stackable":false,"code":"MEMBER15"}' || true
    fi
  done
}
