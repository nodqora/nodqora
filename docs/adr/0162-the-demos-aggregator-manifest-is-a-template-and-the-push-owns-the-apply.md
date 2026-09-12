# ADR-0162: The demo's aggregator manifest is a template, and the script that pushes owns the apply

- **Status**: Accepted
- **Date**: 2026-09-12
- **Ticket**: [Re-applying 40-aggregator.yaml silently reverts the aggregator to an image that was never pushed](https://github.com/nodqora/nodqora/issues/91)

## Context

`demo/k8s/40-aggregator.yaml` named a tag nothing ever pushes:

```yaml
image: ttl.sh/nodqora-market-aggregator:24h
imagePullPolicy: IfNotPresent
```

`publish-aggregator.sh` cannot push that coordinate. ttl.sh reads the **tag** as a
lifetime, so a new version has to change the *repository* name, which the script
did with a timestamp suffix — and then reached the Deployment with
`kubectl set image`. So the manifest's image value was never the image the demo
ran, and `kubectl apply -f demo/k8s/40-aggregator.yaml` rolled the Deployment
back to a coordinate that has never existed in a registry.

`imagePullPolicy: IfNotPresent` is what turned a loud failure into a silent one.
A node holding a layer cached under that name from an earlier run starts the pod
happily — `Running`, then `Ready` — on **whatever it happens to hold**, which may
be an aggregator built from any past commit. Nothing in `kubectl get pods` says
the image is stale, and `status.sh` reports the aggregator healthy while it runs
unknown code. A node without the layer gets `ImagePullBackOff`, which is at least
visible.

This was observed while fixing
[#89](https://github.com/nodqora/nodqora/issues/89): applying the manifest for an
unrelated annotation change rolled a third pod on the placeholder tag, and it
reached `Running` within seconds.

`up.sh` was never exposed — `publish-aggregator.sh` ran immediately after its
apply, so the placeholder never survived a full bring-up. The exposure is the
**ad-hoc re-apply**, which is the ordinary way to change everything else in that
file: the annotations, the replica count, the probes, the resources.

The repository already had an answer to this exact question one directory up.
`compose.yaml` names `ghcr.io/nodqora/nodqora:@VERSION@` — an unresolvable
placeholder, deliberately unusable, so the copy in the tree cannot be mistaken
for the install — and `build.gradle.kts` *asserts the placeholder is present*
before rendering, so the substitution cannot silently stop happening.
`40-aggregator.yaml` had the inverse: a placeholder shaped exactly like a working
tag, which fails by degrading rather than by stopping.

## Decision

**[ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)'s
reasoning about `@VERSION@` generalises, and this is it applied to a manifest
that is not a release asset.** Nothing about that ADR changes: the argument it
makes for `compose.yaml` — that a file naming a coordinate it cannot guarantee
should be unusable rather than plausible — was never specific to a release.

**The manifest names `@IMAGE@`, unquoted, and `publish-aggregator.sh` renders
it** after pushing, piping the result to `kubectl apply -f -`. The script already
owned the coordinate; it now owns the apply, which is the only way the two can be
guaranteed to agree.

**Unquoted is the decision, not an accident of style.** `@` is a reserved
indicator in YAML and cannot start a plain scalar, so kubectl refuses the
unrendered file *client-side and whole*:

```
error: error parsing demo/k8s/40-aggregator.yaml: error converting YAML to JSON:
yaml: line 39: found character that cannot start any token
```

Nothing reaches the cluster, and the Deployment is not touched — not even
partially, and not the `Service` in the same file. A quoted `"@IMAGE@"` would
parse, apply, and fail later at the kubelet with `InvalidImageName`, leaving a
stalled rollout and a `Pending` pod to be noticed. Both beat today's silent
success; only one of them is impossible to half-do. The error message cannot
teach, so the comment on the line above it does, opening with the exact string
the reader just saw.

**The placeholder is asserted before the build.** `publish-aggregator.sh` greps
for it and exits if it is gone, mirroring `build.gradle.kts`'s guard on
`compose.yaml` for the same stated reason — the substitution must not be able to
silently stop happening. The check is anchored to the `image:` key
(`^[[:space:]]*image: @IMAGE@$`) rather than searched for in the file, because
the commentary explaining the placeholder *mentions* it, and a whole-file match
would pass happily on a manifest pinned to a real tag. It runs before the jar and
the push, so a manifest that has lost its placeholder costs nothing.

**`up.sh` no longer applies `40-aggregator.yaml`.** It cannot: the file is not
appliable as it stands. The apply moves into the publish script the bring-up
already invokes, so the Deployment is still created during `up.sh` —
[#88](https://github.com/nodqora/nodqora/issues/88)'s substance is kept, and the
window it left, where the Deployment sat in `ImagePullBackOff` on the placeholder
for the length of one build, closes as a side effect.

**`imagePullPolicy: IfNotPresent` stays.** The comment defending it is correct:
a restarted pod keeps running off the node's cached copy after the 24h expiry,
which is deliberate and is what makes ttl.sh survivable for a demo. It was the
amplifier here, never the cause, and it cannot amplify an unparseable file — no
cache can answer for a name that is not a reference.

Rejected, and why:

- **Dropping `imagePullPolicy: IfNotPresent` and nothing else.** The smallest
  change, and it converts silent-stale into a visible `ImagePullBackOff`. But it
  does not stop the revert — it only makes the revert complain — and it pays for
  that by deleting a property the demo actually relies on, so it makes the demo
  worse at the thing the policy is there for.
- **Pushing to a registry the project owns**, as `demo/README.md` already
  suggests for anything longer-lived. This removes the whole class of problem,
  because the coordinate becomes stable and committable. It costs the anonymous,
  credential-free property that makes ttl.sh right for a demo a stranger is
  invited to bring up, and it puts a demo image in the same namespace as the
  released one. The one-line change is documented for anyone who wants it.
- **A Gradle task asserting the placeholder**, as `compose.yaml` gets. The
  aggregator is deliberately its own Gradle build, outside the root one, and the
  root build has no reason to know `demo/k8s` exists. The guard belongs to the
  thing that renders.
- **Keeping a real tag and teaching people not to apply the file.** This is the
  status quo plus a comment. The failure it has to prevent is a reader applying
  the file for an unrelated reason, which is exactly the reader who has not read
  the comment.

## Consequences

- **`40-aggregator.yaml` is not valid YAML**, so editors, linters and
  `kubectl apply --dry-run` all reject it. That is the mechanism working rather
  than a defect, and rendering it is one `sed` away — but it means the file
  cannot be checked in place, and a future manifest-wide lint would have to
  render first or skip it.
- **The demo's manifests are no longer uniform.** Six of the seven apply
  directly; this one does not. The README's parts list and its ttl.sh note both
  say so, because the cost of this decision is entirely borne by a reader
  expecting `kubectl apply -f` to work on anything in that directory.
- **`up.sh`'s apply block no longer matches the directory listing**, and the gap
  is load-bearing rather than an oversight, so it carries a comment pointing at
  the publish script.
- **Nothing verifies this against a cluster.** No test reaches real
  infrastructure — that is the demo's whole reason to exist — so the guard, the
  parse failure and the rendered apply were checked by hand with
  `kubectl apply --dry-run=client`, and the end-to-end path is exercised only
  when someone runs `up.sh`.
- **Revisit trigger.** The demo needing an image that outlives 24 hours, or a
  second manifest needing a rendered value. The first makes the owned-registry
  option cheaper than this one; the second means the demo wants a render step of
  its own rather than a `sed` in one script.
