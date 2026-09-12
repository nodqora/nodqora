#!/usr/bin/env bash
# Remove everything this demo created. The namespace takes the PVCs with it.
#
# The order is the point of this script. `entityOperator.topicOperator` in 10-kafka.yaml gets a
# Topic Operator, which puts a `strimzi.io/topic-operator` finalizer on every KafkaTopic and clears
# it only once it has deleted that topic from the broker. Deleting the namespace tears the topics,
# the broker and that operator down together, so the clean-up very likely never happens — and a
# namespace cannot finish terminating while anything in it still holds a finalizer, so `market-demo`
# is left in `Terminating` for good. Uninstalling the Helm release first makes it unrecoverable: the
# Topic Operator runs in the `market-entity-operator` Deployment rather than in the release, and
# once the cluster operator is gone nothing recreates it to finish the job.
#
# So the topics go first, while the operator and the broker are both still up.
set -euo pipefail
ns=market-demo

if kubectl get namespace "$ns" >/dev/null 2>&1; then
  echo "==> KafkaTopics, while the Topic Operator is still up to clear their finalizers"
  kubectl -n "$ns" delete kafkatopic --all --timeout=2m || true

  # A broker that is already gone cannot confirm the topic deletions, so the delete above times
  # out with the finalizers still on — and they would outlive the operator removed below. Strip
  # them by hand rather than leave the namespace wedged.
  remaining="$(kubectl -n "$ns" get kafkatopic -o name 2>/dev/null || true)"
  if [ -n "$remaining" ]; then
    echo "    these did not go on their own; dropping their finalizers:"
    while IFS= read -r topic; do
      [ -n "$topic" ] || continue
      echo "      $topic"
      kubectl -n "$ns" patch "$topic" --type=merge -p '{"metadata":{"finalizers":[]}}' >/dev/null || true
    done <<<"$remaining"
  fi
fi

echo "==> Strimzi operator"
helm uninstall strimzi -n "$ns" 2>/dev/null || true

echo "==> namespace"
kubectl delete namespace "$ns" --wait=false

echo "market-demo is terminating; Strimzi's CRDs are cluster-scoped and were left in place."
echo "To remove those too:  kubectl get crd -o name | grep strimzi.io | xargs kubectl delete"
