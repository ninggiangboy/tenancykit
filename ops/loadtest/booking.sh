#!/usr/bin/env bash
# Runs the flash-sale hold scenario through the public gate. Provide a minted customer token and the seeded offering;
# the compose smoke script seeds them. Scale defaults are small enough for a laptop; the phase-15 calibration used
# BOOKING_LOAD_ATTEMPTS=100000 against a 5,000-place session.
set -euo pipefail

cd "$(dirname "$0")/../.."

docker compose exec -T booking \
  env \
  BOOKING_LOAD_BASE_URL="${BOOKING_LOAD_BASE_URL:-http://localhost:8080}" \
  BOOKING_LOAD_TOKEN="${BOOKING_LOAD_TOKEN:?set BOOKING_LOAD_TOKEN}" \
  BOOKING_LOAD_OFFERING="${BOOKING_LOAD_OFFERING:?set BOOKING_LOAD_OFFERING}" \
  BOOKING_LOAD_SESSION="${BOOKING_LOAD_SESSION:-}" \
  BOOKING_LOAD_POOL="${BOOKING_LOAD_POOL:-default}" \
  BOOKING_LOAD_ATTEMPTS="${BOOKING_LOAD_ATTEMPTS:-1000}" \
  BOOKING_LOAD_CONCURRENCY="${BOOKING_LOAD_CONCURRENCY:-64}" \
  ./gradlew -q :apps:app-boot:loadTest

ops/smoke/booking-invariants.sh
