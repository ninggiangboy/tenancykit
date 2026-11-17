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

# Mailer consumes commands and publishes exactly one event, the terminal delivery result, to its own `mailer.events`.
# Its write set is therefore `mailer.events` plus its own queue and dead-letter pair — never another service's exchange.
# `write` must include the DLQ and suite.dlx because declaring a queue with a dead-letter exchange and binding the DLQ
# both check write on the queue, and `mailer.events` because publishing checks write on the exchange. `read` must
# include `mailer.commands` because queue.bind checks read on the source exchange, and its own queue because consuming
# checks read on it. Compare with the billing block above, line for line: every dot is double-escaped, because the
# pattern is pasted into a JSON document and a single `\.` is not a legal JSON escape.
declare_user mailer \
  "${MAILER_BROKER_PASSWORD:?set the mailer broker password}" \
  '^(mailer\\.commands|mailer\\.commands\\.dlq|mailer\\.events|suite\\.dlx)$' \
  '^(mailer\\.commands|mailer\\.commands\\.dlq|mailer\\.events|suite\\.dlx)$' \
  '^(mailer\\.commands|mailer\\.commands\\.dlq|suite\\.dlx)$'

# Notification consumes platform.events on its own queue and publishes exactly one command to mailer.commands. It is the
# only service user with a cross-service publish grant, and `ops/smoke/isolation.sh` asserts it twice: as a refusal for
# every exchange it must not write, and as a positive control for the one it must. It declares no exchange of its own.
#
# `write` must include its queue, its DLQ and suite.dlx because queue.declare with a dead-letter exchange and binding the
# DLQ both check write on the queue. `read` must include platform.events because a queue.bind checks read on the source
# exchange, and its own queue because consuming checks read on it.
declare_user notification \
  "${NOTIFICATION_BROKER_PASSWORD:?set the notification broker password}" \
  '^(notification\\.platform-events|notification\\.platform-events\\.dlq|suite\\.dlx|mailer\\.commands)$' \
  '^(notification\\.platform-events|notification\\.platform-events\\.dlq|suite\\.dlx|mailer\\.commands)$' \
  '^(platform\\.events|notification\\.platform-events|notification\\.platform-events\\.dlq|suite\\.dlx)$'

# Mailroom consumes platform.events and mailer.events on its own queues and publishes exactly one command to
# mailer.commands. It declares no exchange of its own. `write` covers its two queues, their DLQs, suite.dlx and
# mailer.commands (publishing checks write on the exchange). `read` covers both source exchanges (a queue.bind checks
# read on the source) and its own queues (consuming checks read on the queue). It has no write grant on mailer.events,
# which is what the isolation smoke proves.
declare_user mailroom \
  "${MAILROOM_BROKER_PASSWORD:?set the mailroom broker password}" \
  '^(mailroom\\.platform-events|mailroom\\.platform-events\\.dlq|mailroom\\.mailer-events|mailroom\\.mailer-events\\.dlq|suite\\.dlx|mailer\\.commands)$' \
  '^(mailroom\\.platform-events|mailroom\\.platform-events\\.dlq|mailroom\\.mailer-events|mailroom\\.mailer-events\\.dlq|suite\\.dlx|mailer\\.commands)$' \
  '^(platform\\.events|mailer\\.events|mailroom\\.platform-events|mailroom\\.platform-events\\.dlq|mailroom\\.mailer-events|mailroom\\.mailer-events\\.dlq|suite\\.dlx)$'

# Survey consumes platform.events on its own queue and publishes its three facts to survey.events. `write` covers its
# queue, its DLQ, suite.dlx and survey.events (publishing checks write on the exchange); `read` covers platform.events
# (a queue.bind checks read on the source) and its own queues (consuming checks read on the queue). It has no write
# grant on platform.events, which is what the isolation smoke proves.
declare_user survey \
  "${SURVEY_BROKER_PASSWORD:?set the survey broker password}" \
  '^(survey\\.platform-events|survey\\.platform-events\\.dlq|suite\\.dlx|survey\\.events)$' \
  '^(survey\\.platform-events|survey\\.platform-events\\.dlq|suite\\.dlx|survey\\.events)$' \
  '^(platform\\.events|survey\\.platform-events|survey\\.platform-events\\.dlq|suite\\.dlx)$'

# customer and loyalty are replica consumers only: each declares one queue and its dead-letter pair and owns no exchange, so
# its facts leave as webhooks and there is no customer or loyalty exchange to grant. `write` stops at its own queue
# (queue.declare with a dead-letter exchange and queue.bind both check write on the queue, and a publish would check
# write on the exchange), which is what refuses a publish to platform.events. `read` covers platform.events (a
# queue.bind checks read on the source) and its own queues only, so neither user can read the other's queue, Billing's,
# Survey's or a mailer exchange. The suite rule "apps never consume each other's business events" is therefore a broker
# fact and not a convention.
declare_user customer \
  "${CUSTOMER_BROKER_PASSWORD:?set the customer broker password}" \
  '^(customer\\.platform-events|customer\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(customer\\.platform-events|customer\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(platform\\.events|customer\\.platform-events|customer\\.platform-events\\.dlq|suite\\.dlx)$'

declare_user loyalty \
  "${LOYALTY_BROKER_PASSWORD:?set the loyalty broker password}" \
  '^(loyalty\\.platform-events|loyalty\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(loyalty\\.platform-events|loyalty\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(platform\\.events|loyalty\\.platform-events|loyalty\\.platform-events\\.dlq|suite\\.dlx)$'

# Booking is a replica consumer only, like customer and loyalty: it declares one queue and its dead-letter pair and owns no
# exchange, so its ten facts leave as signed webhooks and there is no booking exchange to grant. `write` stops at its
# own queue (declaring a queue with a dead-letter exchange and binding the DLQ both check write on the queue, and a
# publish would check write on the exchange), which is what refuses a publish to platform.events. `read` covers
# platform.events (a queue.bind checks read on the source) and its own queue only.
declare_user booking \
  "${BOOKING_BROKER_PASSWORD:?set the booking broker password}" \
  '^(booking\\.platform-events|booking\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(booking\\.platform-events|booking\\.platform-events\\.dlq|suite\\.dlx)$' \
  '^(platform\\.events|booking\\.platform-events|booking\\.platform-events\\.dlq|suite\\.dlx)$'

# Workflow is a replica consumer on the Tenant side of the suite rule. It binds one queue to platform.events for the
# state-transfer keys and declares one internal queue, `workflow.runnable`, for its own wake-ups; it owns no exchange,
# and the facts it emits leave as signed webhooks. `write` therefore stops at its own queues (declaring a queue with a
# dead-letter exchange and binding the DLQ both check write on the queue, and a publish would check write on the
# exchange), which is what refuses a publish to platform.events. `read` covers platform.events (a queue.bind checks
# read on the source) and its own queues only.
declare_user workflow \
  "${WORKFLOW_BROKER_PASSWORD:?set the workflow broker password}" \
  '^(workflow\\.platform-events|workflow\\.platform-events\\.dlq|workflow\\.runnable|workflow\\.runnable\\.dlq|suite\\.dlx)$' \
  '^(workflow\\.platform-events|workflow\\.platform-events\\.dlq|workflow\\.runnable|workflow\\.runnable\\.dlq|suite\\.dlx)$' \
  '^(platform\\.events|workflow\\.platform-events|workflow\\.platform-events\\.dlq|workflow\\.runnable|workflow\\.runnable\\.dlq|suite\\.dlx)$'
