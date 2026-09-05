#!/usr/bin/env bash
# apply  — create or update every connector in demo/connectors/
# status — one line per connector
# delete — remove them all
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
connect="http://$(kubectl -n market-demo get svc kafka-connect-lb -o jsonpath='{.status.loadBalancer.ingress[0].ip}'):8083"

case "${1:-status}" in
  apply)
    for f in "$here"/connectors/*.json; do
      name=$(jq -r '.name' "$f")
      # PUT /config is create-or-update, so this script is safe to re-run.
      curl -sS -X PUT -H 'Content-Type: application/json' \
        --data "$(jq -c '.config' "$f")" "$connect/connectors/$name/config" \
        | jq -r '"applied \(.name // .message)"'
    done
    ;;
  delete)
    for f in "$here"/connectors/*.json; do
      name=$(jq -r '.name' "$f")
      curl -sS -X DELETE "$connect/connectors/$name" && echo "deleted $name"
    done
    ;;
  status)
    curl -sS "$connect/connectors?expand=status" \
      | jq -r 'to_entries[] | "\(.key)\t\(.value.status.connector.state)\ttasks=\([.value.status.tasks[]|.state]|join(","))"' \
      | sort | column -t
    ;;
  *) echo "usage: $0 [apply|status|delete]" >&2; exit 2 ;;
esac
