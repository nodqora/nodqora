#!/usr/bin/env bash
# Build the aggregator, push it to ttl.sh, and point the Deployment at the new image.
#
# ttl.sh reads the *tag* as a lifetime, so a new version has to change the repository name
# instead — hence the timestamp suffix. It also means the image stops resolving after 24h:
# re-run this script and the Deployment picks up a fresh one.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
image="ttl.sh/nodqora-market-aggregator-$(date +%Y%m%d%H%M%S):24h"

echo "==> building jar"
(cd "$here/aggregator" && "$here/../gradlew" bootJar --no-daemon -q)

echo "==> pushing $image (linux/amd64; the nodes are x86, this laptop is not)"
docker buildx build --platform linux/amd64 -t "$image" --push "$here/aggregator"

echo "==> rolling out"
kubectl -n market-demo set image deployment/market-aggregator "aggregator=$image"
kubectl -n market-demo rollout status deployment/market-aggregator --timeout=5m

echo "$image"
