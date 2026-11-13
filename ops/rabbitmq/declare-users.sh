#!/bin/sh
# Declares one broker user per service and limits each to its own exchanges and queues.
#
# This runs as a one-shot container after the broker is healthy, not from a definitions file, because the passwords
# must come from the environment rather than be committed as hashes. The management HTTP API is used so the step
# needs no Erlang cookie or node name; POSIX sh and curl are all it needs.
#
# Permissions follow RabbitMQ's resource model: publishing needs write on the exchange; consuming needs read on the
# queue; declaring a queue with a dead-letter exchange needs write on the queue and read on that exchange; binding
# needs write on the queue and read on the source exchange. A service can therefore publish only to its own exchange
# and read only its own queues.
set -eu

BROKER="${BROKER_URL:-http://rabbitmq:15672}"
ADMIN_USER="${ADMIN_USER:-rabbitmq}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?set an administrator password}"

api() {
  local_method="$1"
  local_path="$2"
  local_body="${3:-}"
  if [ -n "${local_body}" ]; then
    curl --silent --show-error --fail-with-body \
      --user "${ADMIN_USER}:${ADMIN_PASSWORD}" --request "${local_method}" \
      --header "Content-Type: application/json" --data "${local_body}" \
      "${BROKER}/api/${local_path}"
  else
    curl --silent --show-error --fail-with-body \
      --user "${ADMIN_USER}:${ADMIN_PASSWORD}" --request "${local_method}" \
      "${BROKER}/api/${local_path}"
  fi
}

declare_user() {
  # Arguments: user password configure write read
  api PUT "users/$1" "{\"password\":\"$2\",\"tags\":\"\"}" > /dev/null
  api PUT "permissions/%2F/$1" "{\"configure\":\"$3\",\"write\":\"$4\",\"read\":\"$5\"}" > /dev/null
  echo "declared $1 with its own exchanges and queues"
}

waited=0
until api GET overview > /dev/null 2>&1; do
  waited=$((waited + 1))
  if [ "${waited}" -ge 60 ]; then
    echo "the broker did not answer at ${BROKER}" >&2
    exit 1
  fi
  sleep 1
done

# Platform publishes its state-transfer events and declares only its own exchange and the suite's dead-letter exchange.
declare_user platform \
  "${PLATFORM_BROKER_PASSWORD:?set the platform broker password}" \
  '^(platform\\.events|suite\\.dlx)$' \
  '^(platform\\.events|suite\\.dlx)$' \
  '^(platform\\.events|suite\\.dlx)$'

# Billing declares its consumer queue and, because the topology declares every exchange a queue uses, also declares
# platform.events and the dead-letter exchange. Its write permission covers its own exchange and its own queue (a
# queue.declare with a dead-letter exchange and a queue.bind both check write on the queue) but never
# platform.events, so publishing there is refused — which is what the isolation check proves.
declare_user billing \
  "${BILLING_BROKER_PASSWORD:?set the billing broker password}" \
  '^(platform\\.events|billing\\.events|billing\\.platform-events|billing\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(billing\\.events|billing\\.platform-events|billing\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(platform\\.events|billing\\.platform-events|suite\\.dlx)$'
