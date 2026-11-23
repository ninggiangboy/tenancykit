#!/usr/bin/env bash
# Booking: resources with weekly hours, a closed day and a maintenance block; a group; interval offerings (appointments,
# room hire) and capacity offerings (classes, a rush session behind a waiting room); sessions from RRULEs; bookings that
# are held, confirmed, cancelled and no-shows; a full session with a waitlist; and a weekly series. Dates are relative to
# today, so the data is always in the bookable window. Sourced by seed.sh.

BOOKING_ZONE="Asia/Ho_Chi_Minh"

# Seven days, 07:00-21:00.
booking_hours() {
  local d out="["
  for d in 1 2 3 4 5 6 7; do out="${out}{\"weekday\":${d},\"opens\":\"07:00\",\"closes\":\"21:00\"},"; done
  printf '%s]' "${out%,}"
}

# N days ahead at HH:MM local time. An instant carries the zone's offset; a recurrence start is local to its zone.
booking_at() { printf '%sT%s:00+07:00' "$(iso_date "+$1")" "$2"; }
booking_local() { printf '%sT%s:00' "$(iso_date "+$1")" "$2"; }

seed_booking_tenant() {
  local code="$1" people="$2"
  use_app "${code}" booking "${PROXY}/booking/system/v1"
  acall_try "gate" PUT /settings/gate "{\"allowedOrigins\":[\"http://${code}.localhost:8090\"],\"maxTokenTtlSeconds\":900}" || true

  # --- resources: staff and rooms ---
  local resources=() entry rcode rname seats rid
  if [ "${code}" = globex ]; then
    resources=("trainer-ann|Trainer Ann|" "trainer-bao|Trainer Bao|" "trainer-chi|Trainer Chi|" "studio-a|Studio A|15" "studio-b|Studio B|8")
  else
    resources=("tasting-bar|Tasting bar|6" "roastery-guide|Roastery guide|" "meeting-room|Meeting room|8")
  fi
  local ids_file="${SEED_OUT}/booking-${code}.ids"
  : > "${ids_file}"
  for entry in "${resources[@]}"; do
    IFS='|' read -r rcode rname seats <<< "${entry}"
    ensure_code /resources "${rcode}" "$(jq -nc --arg c "${rcode}" --arg n "${rname}" --arg z "${BOOKING_ZONE}" --arg s "${seats}" \
      '{code: $c, name: $n, zone: $z} + (if $s == "" then {} else {seats: ($s | tonumber)} end)')" || continue
    rid="${ENSURED_ID}"
    printf '%s=%s\n' "${rcode}" "${rid}" >> "${ids_file}"
    acall_try "hours ${rcode}" PUT "/resources/${rid}/working-hours" "{\"hours\":$(booking_hours)}" || true
  done
  rid_of() { sed -n "s/^$1=//p" "${ids_file}"; }

  # A closed day for one resource and a maintenance block for another: the two ways availability shrinks.
  local first_room
  first_room=$([ "${code}" = globex ] && rid_of studio-b || rid_of meeting-room)
  [ -z "${first_room}" ] || acall_try "closed day" POST "/resources/${first_room}/exceptions" \
    "{\"fromDate\":\"$(iso_date +9)\",\"toDate\":\"$(iso_date +9)\",\"kind\":\"CLOSED\"}" || true
  [ -z "${first_room}" ] || acall_try "block" POST /blocks \
    "{\"resourceId\":\"${first_room}\",\"reason\":\"Floor maintenance\",\"startsAt\":\"$(booking_at 4 13:00)\",\"endsAt\":\"$(booking_at 4 15:00)\"}" || true

  if [ "${code}" = globex ]; then
    # --- a group of trainers, an appointment offering that books whoever is free ---
    ensure_code /resource-groups trainers "$(jq -nc --arg a "$(rid_of trainer-ann)" --arg b "$(rid_of trainer-bao)" --arg c "$(rid_of trainer-chi)" \
      '{code: "trainers", name: "Trainers", strategy: "LEAST_BUSY", members: [{resourceId: $a, priority: 1}, {resourceId: $b, priority: 2}, {resourceId: $c, priority: 3}]}')" || true
    local group="${ENSURED_ID}"
    ensure_code /offerings personal-training '{"code":"personal-training","name":"Personal training","kind":"INTERVAL","durationMin":60,"slotStep":30,"horizonDays":30,"bufferAfter":10}' && PT="${ENSURED_ID}"
    [ -z "${group}" ] || [ -z "${PT}" ] || acall_try "requirements" PUT "/offerings/${PT}/requirements" "{\"requirements\":[{\"slotCode\":\"trainer\",\"groupId\":\"${group}\"}]}" || true
    [ -z "${PT}" ] || acall_try "cancellation windows" PUT "/offerings/${PT}/cancellation-windows" '{"windows":[{"minutesBeforeStart":1440,"code":"FREE"},{"minutesBeforeStart":240,"code":"LATE"}]}' || true
    ensure_code /offerings yoga '{"code":"yoga","name":"Yoga flow","kind":"CAPACITY","durationMin":60,"slotStep":60,"horizonDays":30,"publicShowsRemaining":true}' && YOGA="${ENSURED_ID}"
    ensure_code /offerings hiit-rush '{"code":"hiit-rush","name":"HIIT launch night","kind":"CAPACITY","durationMin":45,"slotStep":45,"horizonDays":30,"queueMode":"REQUIRED","admitPerSecond":20,"admitWindowSeconds":120}' && HIIT="${ENSURED_ID}"
    SESSIONS_YOGA="" SESSIONS_HIIT=""
    [ -z "${YOGA}" ] || SESSIONS_YOGA=$(booking_sessions "${YOGA}" "$(booking_local 1 18:00)" 'RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=9' 12)
    [ -z "${HIIT}" ] || SESSIONS_HIIT=$(booking_sessions "${HIIT}" "$(booking_local 2 19:00)" 'RRULE:FREQ=DAILY;COUNT=2' 2)
    booking_appointments "${PT}" "${people}" 60
    booking_full_session_and_waitlist "${HIIT}" "${SESSIONS_HIIT%% *}"
    # a few people in the yoga class, one cancelled
    local ref n=0 first_yoga="${SESSIONS_YOGA%% *}"
    for ref in G-001 G-002 G-004 G-005; do
      [ -z "${first_yoga}" ] || acall_try "yoga ${ref}" POST /bookings "{\"offeringId\":\"${YOGA}\",\"customerRef\":\"${ref}\",\"sessionId\":\"${first_yoga}\",\"poolCode\":\"default\",\"qty\":1}" "seed-booking-${code}-yoga-${ref}" \
        && acall_try "confirm" POST "/bookings/$(pick '.id')/confirm" '{}' || true
    done
    # a weekly personal-training series for a member
    [ -z "${PT}" ] || acall_try "series" POST /series "{\"offeringId\":\"${PT}\",\"customerRef\":\"G-001\",\"zone\":\"${BOOKING_ZONE}\",\"dtstart\":\"$(booking_local 3 08:00)\",\"rule\":[\"RRULE:FREQ=WEEKLY;COUNT=4\"],\"durationMinutes\":60,\"mode\":\"SKIP_CONFLICTS\"}" "seed-series-${code}" || true
  else
    ensure_code /offerings tasting '{"code":"tasting","name":"Coffee tasting","kind":"INTERVAL","durationMin":45,"slotStep":45,"horizonDays":30}' && PT="${ENSURED_ID}"
    [ -z "${PT}" ] || acall_try "requirements" PUT "/offerings/${PT}/requirements" "{\"requirements\":[{\"slotCode\":\"bar\",\"resourceId\":\"$(rid_of tasting-bar)\"}]}" || true
    ensure_code /offerings roastery-tour '{"code":"roastery-tour","name":"Roastery tour","kind":"CAPACITY","durationMin":60,"slotStep":60,"horizonDays":30}' && TOUR="${ENSURED_ID}"
    [ -z "${TOUR}" ] || booking_sessions "${TOUR}" "$(booking_local 2 10:00)" 'RRULE:FREQ=WEEKLY;BYDAY=SA;COUNT=4' 10 > /dev/null
    booking_appointments "${PT}" "${people}" 45
  fi
}

# The session ids of an offering (space separated, soonest first). A series is created only when the offering has no
# sessions yet, so a second run adds no duplicates.
booking_sessions() {
  local offering="$1" start="$2" rule="$3" capacity="$4" ids
  booking_session_ids() {
    acall GET "/sessions?limit=100"
    printf '%s' "${HTTP_BODY}" | jq -r --arg o "${offering}" '(.data // []) | map(select(.offeringId == $o)) | sort_by(.startsAt, .id) | map(.id) | join(" ")' 2>/dev/null
  }
  ids=$(booking_session_ids)
  if [ -z "${ids}" ]; then
    acall_try "sessions" POST /session-series "{\"offeringId\":\"${offering}\",\"zone\":\"${BOOKING_ZONE}\",\"dtstart\":\"${start}\",\"rule\":[\"${rule}\"],\"durationMinutes\":60,\"capacity\":${capacity}}" || true
    ids=$(booking_session_ids)
  fi
  printf '%s' "${ids}"
}

# Appointments for the people given (space separated), in the days ahead: most confirmed, one left held, one cancelled,
# one rescheduled.
booking_appointments() {
  local offering="$1" people="$2" minutes="$3" ref n=0 day hour id
  [ -n "${offering}" ] || return 0
  for ref in ${people}; do
    day=$(( n % 6 + 1 ))
    hour=$(printf '%02d:%02d' $(( 9 + n % 7 )) $(( (n % 2) * 30 ))) 
    acall_try "booking ${ref}" POST /bookings "{\"offeringId\":\"${offering}\",\"customerRef\":\"${ref}\",\"start\":\"$(booking_at "${day}" "${hour}")\",\"durationMinutes\":${minutes}}" "seed-booking-${A_CODE}-${ref}-${n}" || { n=$((n + 1)); continue; }
    id=$(pick '.id')
    case $(( n % 5 )) in
      0 | 1 | 2) acall_try "confirm ${ref}" POST "/bookings/${id}/confirm" '{}' || true ;;
      3) acall_try "confirm ${ref}" POST "/bookings/${id}/confirm" '{}' && acall_try "cancel ${ref}" POST "/bookings/${id}/cancel" || true ;;
      4) ;;  # stays HELD until its hold lapses
    esac
    [ "${n}" -ne 1 ] || acall_try "reschedule ${ref}" POST "/bookings/${id}/reschedule" "{\"start\":\"$(booking_at $((day + 1)) 16:00)\",\"durationMinutes\":${minutes}}" || true
    n=$((n + 1))
  done
}

# A two-seat session filled by two people, then three more join the waitlist.
booking_full_session_and_waitlist() {
  local offering="$1" session="$2" ref
  [ -n "${offering}" ] && [ -n "${session}" ] || return 0
  for ref in G-001 G-004; do
    acall_try "hiit ${ref}" POST /bookings "{\"offeringId\":\"${offering}\",\"customerRef\":\"${ref}\",\"sessionId\":\"${session}\",\"poolCode\":\"default\",\"qty\":1}" "seed-booking-${A_CODE}-hiit-${ref}" \
      && acall_try "confirm" POST "/bookings/$(pick '.id')/confirm" '{}' || true
  done
  for ref in G-002 G-003 G-005; do
    acall_try "waitlist ${ref}" POST /waitlist "{\"offeringId\":\"${offering}\",\"customerRef\":\"${ref}\",\"qty\":1,\"sessionId\":\"${session}\",\"poolCode\":\"default\"}" || true
  done
}

seed_booking() {
  step "Booking: globex"
  seed_booking_tenant globex "G-001 G-002 G-003 G-004 G-005 G-006 G-001 G-004"
  step "Booking: acme"
  seed_booking_tenant acme "C-001 C-002 C-006 C-009 C-013"
}
