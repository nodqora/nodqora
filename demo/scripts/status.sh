#!/usr/bin/env bash
# Where everything is, and whether data is moving.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
ns=market-demo
ip() { kubectl -n $ns get svc "$1" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null; }

echo "== endpoints =="
printf "kafka bootstrap  %s:9094\n" "$(ip market-kafka-external-bootstrap)"
printf "connect REST     http://%s:8083\n" "$(ip kafka-connect-lb)"
printf "opensearch       http://%s:9200\n" "$(ip opensearch-lb)"
printf "kafka console    http://%s\n" "$(ip kafka-ui)"

echo
echo "== workloads =="
kubectl -n $ns get deploy -o custom-columns='NAME:.metadata.name,READY:.status.readyReplicas,DESIRED:.spec.replicas' 2>/dev/null

echo
echo "== connectors =="
"$here/scripts/connectors.sh" status

echo
echo "== topic ends =="
for t in market.trades.btc-usd market.trades.eth-usd market.ohlc.1m; do
  kubectl -n $ns exec market-dual-role-0 -- \
    bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$t" 2>/dev/null || true
done

echo
echo "== consumer group lag =="
kubectl -n $ns exec market-dual-role-0 -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group market-aggregator 2>/dev/null \
  | awk 'NR==1 || $6 != "-"' | cut -c1-120

echo
echo "== sinks =="
printf "mongo docs       "
kubectl -n $ns exec deploy/mongodb -- mongosh --quiet --eval 'db.getSiblingDB("market").ohlc_1m.countDocuments({})' 2>/dev/null
printf "opensearch docs  "
curl -s "http://$(ip opensearch-lb):9200/market.ohlc.1m/_count" | jq -r '.count // "index not created yet"'
