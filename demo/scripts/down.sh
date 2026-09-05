#!/usr/bin/env bash
# Remove everything this demo created. The namespace takes the PVCs with it.
set -euo pipefail
helm uninstall strimzi -n market-demo 2>/dev/null || true
kubectl delete namespace market-demo --wait=false
echo "market-demo is terminating; Strimzi's CRDs are cluster-scoped and were left in place."
echo "To remove those too:  kubectl get crd -o name | grep strimzi.io | xargs kubectl delete"
