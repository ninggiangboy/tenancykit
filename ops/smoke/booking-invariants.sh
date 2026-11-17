#!/usr/bin/env bash
# Runs booking's invariant SQL as the schema owner so every tenant is covered. Any output row is a violation and makes
# the script fail; the compose stack's owner credentials are read the same way the migrate step reads them.
set -euo pipefail

cd "$(dirname "$0")/../.."

if ! command -v docker >/dev/null 2>&1; then
  echo "booking-invariants: docker is required" >&2
  exit 2
fi

output=$(docker compose exec -T postgres psql \
  -U "${BOOKING_MIGRATION_DB_USER:-booking_owner}" \
  -d booking \
  -v ON_ERROR_STOP=1 \
  -f /dev/stdin < ops/smoke/booking-invariants.sql)

violations=$(printf '%s\n' "$output" | grep -E '^(capacity oversold|unit owned|active booking|overlapping interval|held too long)' || true)
if [ -n "$violations" ]; then
  printf '%s\n' "$violations" >&2
  exit 1
fi
printf 'booking invariants: no violations\n'
