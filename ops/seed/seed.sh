#!/usr/bin/env bash
# Seeds a LOCAL stack with a complete demo: tenants, people, and data in every app, so each screen and each case can
# be looked at or tested straight away. Safe to run again; it reuses what exists.
#
#   ops/seed/seed.sh                      # everything
#   ops/seed/seed.sh billing loyalty      # only these modules (a fresh stack needs `platform` first)
#
# Modules: platform billing customer loyalty booking mailroom survey attribution workflow.
# Needs the stack up (`make up-all`) and the console published (`make console`). Requirements: curl, jq, ruby, uuidgen.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

# shellcheck disable=SC1091
. ops/seed/lib.sh

case "${PROXY}" in
  http://localhost:*|http://127.0.0.1:*|http://*.localhost:*) ;;
  *) fail "the seed writes demo data and only runs against a local stack, not ${PROXY}" ;;
esac

for module in platform billing customer loyalty booking mailroom survey attribution workflow summary; do
  # shellcheck disable=SC1090
  . "ops/seed/${module}.sh"
done

MODULES=("$@")
[ ${#MODULES[@]} -gt 0 ] || MODULES=(platform billing customer loyalty booking mailroom survey attribution workflow)
for module in "${MODULES[@]}"; do
  "seed_${module}"
  [ "${module}" != platform ] || seed_console_remotes
done

seed_summary

if [ ${#NOTES[@]} -gt 0 ]; then
  echo
  echo "Done, with ${#NOTES[@]} note(s) (steps the demo can live without):"
  printf '  - %s\n' "${NOTES[@]}"
else
  echo
  echo "Done."
fi
