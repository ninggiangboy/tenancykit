#!/usr/bin/env bash
# Runs one suite service on the host with Gradle's bootRun instead of in a container. Its settings come from the
# service's own block in docker-compose.yml (so .env stays the single source of secrets and role names); only the
# addresses of the things that stay in Docker are rewritten from container names to the ports compose publishes.
#
#   ops/dev/run-service.sh <service>            migrate (when the service has a schema step), then run api + worker
#   DRY_RUN=1 ops/dev/run-service.sh <service>  print what would run, without starting anything
#
# Needs the infrastructure up (`make infra`). Each service listens on its own host port, listed below, so the whole
# suite can run side by side.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

# service | build directory | compose service that carries its settings | compose migrate step ('-' for none) | port
SERVICES="
platform|backend/services/platform|platform|-|8082
billing|backend/services/billing|billing|billing-migrate|8080
notification|backend/services/notification|notification-api|notification-migrate|8085
mailroom|backend/services/mailroom|mailroom-api|mailroom-migrate|8086
customer|backend/services/customer|customer-api|customer-migrate|8087
loyalty|backend/services/loyalty|loyalty-api|loyalty-migrate|8088
mailer|backend/services/mailer|mailer-worker|mailer-migrate|8084
survey|backend/services/survey|survey-api|survey-migrate|8091
booking|backend/services/booking|booking-api|booking-migrate|8092
workflow|backend/services/workflow|workflow-api|workflow-migrate|8093
attribution|backend/services/attribution|attribution-api|attribution-migrate|8094
"

name="${1:-}"
row="$(printf '%s\n' "${SERVICES}" | grep "^${name}|" || true)"
if [ -z "${name}" ] || [ -z "${row}" ]; then
  echo "usage: $0 <service>   one of: $(printf '%s\n' "${SERVICES}" | cut -d'|' -f1 | grep . | tr '\n' ' ')" >&2
  exit 2
fi
IFS='|' read -r _ dir compose_service migrate_service port <<< "${row}"

COMPOSE="${COMPOSE:-docker compose}"
CONFIG="$(${COMPOSE} config --format json)"

# Prints "KEY=VALUE" for the compose service's environment, NUL-terminated, with container addresses turned into the
# host ports compose publishes. JAVA_TOOL_OPTIONS is dropped: its heap cap is sized for a container, not this machine.
service_env() {
  printf '%s' "${CONFIG}" | jq -j --arg s "$1" '
    def host:
      if . == "rabbitmq" or . == "mailpit" then "localhost"
      else gsub("//postgres:5432"; "//localhost:5432")
        | gsub("//keycloak:8080"; "//localhost:8081")
        | gsub("//seaweedfs:9000"; "//localhost:9000")
        | gsub("//billing:8080"; "//localhost:8080")
        | gsub("platform\\.localhost:8080"; "platform.localhost:8082")
      end;
    .services[$s].environment
    | del(.JAVA_TOOL_OPTIONS)
    | to_entries[] | "\(.key)=\(.value | tostring | host)\u0000"'
}

service_profiles() {
  printf '%s' "${CONFIG}" | jq -r --arg s "$1" '.services[$s].environment.SPRING_PROFILES_ACTIVE // ""'
}

# Runs bootRun for one compose service's settings in a subshell, so nothing leaks into the next step.
boot() {
  local settings="$1" profiles="$2" entry
  (
    while IFS= read -r -d '' entry; do export "${entry}"; done < <(service_env "${settings}")
    export SERVER_PORT="${port}"
    if [ -n "${profiles}" ]; then export SPRING_PROFILES_ACTIVE="${profiles}"; else unset SPRING_PROFILES_ACTIVE; fi
    echo "==> ${name}: bootRun (profiles: ${profiles:-default}, port ${port})"
    if [ "${DRY_RUN:-0}" = "1" ]; then
      echo "    cd ${dir} && ./gradlew :apps:app-boot:bootRun"
    else
      cd "${dir}" && ./gradlew :apps:app-boot:bootRun
    fi
  )
}

if [ "${migrate_service}" != "-" ]; then
  boot "${migrate_service}" "$(service_profiles "${migrate_service}")"
fi

# One process serves both runtimes of an app that splits them: the api profiles compose gives the api container, plus
# worker. An app without a runtime split (Platform) keeps whatever compose gives it.
profiles="$(service_profiles "${compose_service}")"
case ",${profiles}," in *,api,*) profiles="${profiles},worker" ;; esac
boot "${compose_service}" "${profiles}"
