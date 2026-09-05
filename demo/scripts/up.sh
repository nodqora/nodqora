#!/usr/bin/env bash
# Bring the whole demo pipeline up in a Kubernetes cluster, from nothing.
#
#   Coinbase → 2 HTTP source connectors → 2 raw topics → Kafka Streams service
#            → market.ohlc.1m → Mongo sink + OpenSearch sink
#
# Assumes MetalLB (or another LoadBalancer) and a StorageClass named `local-path`. Adjust
# demo/k8s/*.yaml if yours differ.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
ns=market-demo

echo "==> namespace"
kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -

echo "==> Strimzi operator"
helm repo add strimzi https://strimzi.io/charts/ >/dev/null 2>&1 || true
helm repo update strimzi >/dev/null
helm upgrade --install strimzi strimzi/strimzi-kafka-operator \
  -n "$ns" --version 1.2.0 --wait --timeout 5m

echo "==> Kafka, topics, stores, Connect, console"
kubectl apply -f "$here/k8s/10-kafka.yaml"
kubectl -n "$ns" wait kafka/market --for=condition=Ready --timeout=10m
kubectl apply -f "$here/k8s/11-topics.yaml"
kubectl apply -f "$here/k8s/20-mongodb.yaml"
kubectl apply -f "$here/k8s/21-opensearch.yaml"
kubectl apply -f "$here/k8s/30-kafka-connect.yaml"
kubectl apply -f "$here/k8s/50-kafka-ui.yaml"

echo "==> waiting for Connect to load its three plugins"
kubectl -n "$ns" rollout status deployment/kafka-connect --timeout=10m
kubectl -n "$ns" rollout status deployment/mongodb --timeout=5m
kubectl -n "$ns" rollout status deployment/opensearch --timeout=10m

echo "==> connectors"
"$here/scripts/connectors.sh" apply

echo "==> aggregator (builds, pushes to ttl.sh, deploys)"
"$here/scripts/publish-aggregator.sh"

"$here/scripts/status.sh"
