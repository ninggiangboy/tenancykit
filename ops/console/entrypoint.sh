#!/bin/sh
# Renders the runtime configuration from the environment and starts the static server. Nothing here is secret: the
# Keycloak authority and the public client id are all a browser needs to start a sign-in.
set -eu

: "${KEYCLOAK_AUTHORITY:=http://localhost:8081/realms/billing}"
: "${KEYCLOAK_CLIENT_ID:=suite-console}"

sed \
  -e "s|__KEYCLOAK_AUTHORITY__|${KEYCLOAK_AUTHORITY}|g" \
  -e "s|__KEYCLOAK_CLIENT_ID__|${KEYCLOAK_CLIENT_ID}|g" \
  /etc/console/config.json.template > /tmp/config.json

exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
