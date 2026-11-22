#!/usr/bin/env bash
# Writes one Scalar page per caller, plus a landing page, into the directory `make docs` serves. The sources are the
# per-caller OpenAPI files under contracts/openapi/<caller>/, so a caller page lists only the apps that have an
# operation for that caller.
set -euo pipefail

OUT="${1:?usage: make-pages.sh <output-directory>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMPLATE="$ROOT/ops/docs/caller.html.template"
OPENAPI="$ROOT/contracts/openapi"

mkdir -p "$OUT"

# The canonical order, so the landing page does not depend on directory listing order.
CALLERS=(tenant-user tenant-system platform-user customer-session public provider internal)

caller_title() {
  case "$1" in
    platform-user) echo "Platform users" ;;
    tenant-user) echo "Tenant users" ;;
    tenant-system) echo "Tenant systems" ;;
    customer-session) echo "Customer sessions" ;;
    public) echo "Public" ;;
    provider) echo "Providers" ;;
    internal) echo "Internal services" ;;
    *) echo "$1" ;;
  esac
}

app_title() {
  case "$1" in
    platform) echo "Platform" ;;
    billing) echo "Billing" ;;
    notification) echo "Notification" ;;
    mailroom) echo "Mailroom" ;;
    survey) echo "Survey" ;;
    attribution) echo "Attribution" ;;
    workflow) echo "Workflow" ;;
    booking) echo "Booking" ;;
    customer) echo "Customer" ;;
    loyalty) echo "Loyalty" ;;
    *) echo "$1" ;;
  esac
}

present=()
for caller in "${CALLERS[@]}"; do
  dir="$OPENAPI/$caller"
  [ -d "$dir" ] || continue
  apps=()
  while IFS= read -r app; do apps+=("$app"); done < <(find "$dir" -maxdepth 1 -name '*.yaml' -exec basename {} .yaml \; | sort)
  [ "${#apps[@]}" -gt 0 ] || continue

  sources=""
  first=1
  for app in "${apps[@]}"; do
    default=""
    if [ "$first" -eq 1 ]; then default=", default: true"; fi
    sources+="          { title: \"$(app_title "$app")\", slug: \"$app\", url: \"./openapi/$caller/$app.yaml\"$default },"
    sources+=$'\n'
    first=0
  done

  title="$(caller_title "$caller")"
  python3 - "$TEMPLATE" "$OUT/$caller.html" "$title" "$caller" "$sources" <<'PY'
import sys
template, target, title, caller, sources = sys.argv[1:6]
text = (open(template, encoding="utf-8").read()
        .replace("__TITLE__", title)
        .replace("__CALLER__", caller)
        .replace("__SOURCES__", sources.rstrip("\n")))
open(target, "w", encoding="utf-8").write(text)
PY
  present+=("$caller")
done

python3 - "$OUT/index.html" "${present[@]}" <<'PY'
import sys
target, *callers = sys.argv[1:]
titles = {
    "platform-user": "Platform users",
    "tenant-user": "Tenant users",
    "tenant-system": "Tenant systems",
    "customer-session": "Customer sessions",
    "public": "Public",
    "provider": "Providers",
    "internal": "Internal services",
}
links = "\n".join(
    f'      <li><a href="./{caller}.html">{titles.get(caller, caller)}</a></li>' for caller in callers
)
page = f"""<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>tenancykit API reference</title>
    <style>
      body {{ font: 16px/1.5 system-ui, sans-serif; margin: 3rem auto; max-width: 42rem; padding: 0 1rem; }}
      code {{ background: #f3f3f3; padding: 0.1rem 0.3rem; border-radius: 3px; }}
      li {{ margin: 0.4rem 0; }}
    </style>
  </head>
  <body>
    <h1>tenancykit API reference</h1>
    <p>One page per caller. Each page lists the apps that have an operation for that caller.</p>
    <ul>
{links}
    </ul>
    <p>Generated from <code>contracts/openapi/&lt;caller&gt;/&lt;app&gt;.yaml</code>.</p>
  </body>
</html>
"""
open(target, "w", encoding="utf-8").write(page)
PY

echo "Wrote $(printf '%s ' "${present[@]}")pages to $OUT"
