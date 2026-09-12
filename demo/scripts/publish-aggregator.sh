#!/usr/bin/env bash
# Build the aggregator, push it to ttl.sh, and apply the Deployment at that image.
#
# ttl.sh reads the *tag* as a lifetime, so a new version has to change the repository name
# instead — hence the timestamp suffix. It also means the image stops resolving after 24h:
# re-run this script and the Deployment picks up a fresh one.
#
# ADR-0162: this script owns the apply, because it owns the coordinate. `40-aggregator.yaml`
# names `@IMAGE@` and is rendered here, so the manifest cannot be applied behind this script's
# back at an image nothing pushed.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$here/k8s/40-aggregator.yaml"
image="ttl.sh/nodqora-market-aggregator-$(date +%Y%m%d%H%M%S):24h"

# Written once and used by both the guard and the render below, so the two cannot drift apart if
# the manifest's indentation or key ever changes. The capture group is what the render needs; to
# `grep -E` it is just a group.
placeholder_re='^([[:space:]]*)image: @IMAGE@$'

# The placeholder is checked before the build, so a manifest that has lost it costs nothing. This
# matches `build.gradle.kts`'s guard on `compose.yaml`, and for the same reason: the substitution
# must not be able to silently stop happening. Anchored to the `image:` key rather than searched
# for in the file, because the comment above that key mentions the placeholder too — a whole-file
# match would pass happily on a manifest pinned to a real tag.
if ! grep -qE "$placeholder_re" "$manifest"; then
  echo "40-aggregator.yaml does not name \`image: @IMAGE@\`, so there is nothing here to render," >&2
  echo "and the apply below would deploy whatever tag it does name instead — which is nothing this" >&2
  echo "script ever pushed (ADR-0162)." >&2
  echo >&2
  echo "Restore the placeholder as the container's image, indented as the rest of the block is:" >&2
  echo >&2
  echo "    image: @IMAGE@" >&2
  echo >&2
  echo "To deploy your own registry's copy instead, change the \`image=\` line in this script." >&2
  exit 1
fi

echo "==> building jar"
(cd "$here/aggregator" && "$here/../gradlew" bootJar --no-daemon -q)

echo "==> pushing $image (linux/amd64; the nodes are x86, this laptop is not)"
docker buildx build --platform linux/amd64 -t "$image" --push "$here/aggregator"

echo "==> applying the Deployment at $image"
# `&` is the whole-match backreference in a sed replacement, so it is escaped rather than trusted:
# the README invites changing the `image=` line above to a registry of your own.
sed -E "s|$placeholder_re|\1image: ${image//&/\\&}|" "$manifest" | kubectl apply -f -
kubectl -n market-demo rollout status deployment/market-aggregator --timeout=5m

echo "$image"
