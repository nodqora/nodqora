# ADR-0148: A `pods` backing names a selector, and readiness is counted from the live pods it matches

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [`kubernetes` sees nothing of a Strimzi-run Kafka or Connect](https://github.com/fredskor/nodqora/issues/36)
- **Amends**: ADR-0030, ADR-0034

## Context

ADR-0030 decides which Kubernetes objects become nodes and, in passing, fixes
what a `kubernetes` backing looks like: `{ plugin: kubernetes, kind: <lowercased
kind>, reference: <namespace>/<name> }`. ADR-0034 then says which of those
backings health may read — *"only workload backings contribute"* — and the two
together mean a `kubernetes` observation always starts from **one named object of
a kind this plugin lists**.

[#36](https://github.com/fredskor/nodqora/issues/36) found the case where no such
object exists. Strimzi has not used StatefulSets since 0.35: a broker and a
Connect worker are both `StrimziPodSet`, a CRD with its own pods.

```text
$ kubectl -n market-demo get sts,strimzipodsets
No resources found of kind StatefulSet.
NAME                                             PODS   READY PODS   CURRENT PODS
strimzipodset.core.strimzi.io/market-dual-role   1      1            1
```

The broker is no loss — a broker is not a node in this model, topics are
(ADR-0036). The Connect worker is. ADR-0022 has `connect` stamp its configured
workload onto **every** connector it discovers, and on Strimzi that configuration
has nothing to name, so every connector node loses its third backing and the
readiness half of its raw signal, with no configuration that fixes it. Strimzi is
by a wide margin the usual way to run Kafka on Kubernetes.

The ticket's first answer was to document it, and
`docs/running-against-your-own-cluster.md` does. A doc that says *this does not
work* is honest and is not a fix.

Its third answer — teach `kubernetes` to read `StrimziPodSet` — is wrong in kind
rather than in difficulty. ADR-0030's list is workload kinds **the platform**
defines; naming one vendor's CRD in a plugin's produced kinds invites the next
one, and the one after that.

## Decision

**A backing of kind `pods` names a label selector, and this plugin reads its
readiness by counting the live pods that match.**

```yaml
connect:
  workload:
    plugin: kubernetes
    kind: pods
    reference: market-demo/strimzi.io/cluster=market,strimzi.io/kind=KafkaConnect
```

- **Nothing about a plugin's configuration contract changes.** ADR-0022 already
  has `connect` stamp an opaque `(plugin, kind, reference)` triple that it never
  interprets, so the whole of the new vocabulary lands in the `kubernetes`
  plugin, where it belongs. This is what separates it from the rejected third
  option: `pods` is core Kubernetes, and it is one word rather than one word per
  operator.

- **The reference is `<namespace>/<key>=<value>[,…]`, split at the first slash
  and nowhere else.** A label key is routinely a DNS subdomain —
  `strimzi.io/cluster`, `app.kubernetes.io/name` — so slashes inside the selector
  are the normal case and the namespace is the only segment that can be delimited
  unambiguously. Matching is **equality only and AND-ed**, which is both the
  `kubectl -l` shorthand and exactly the semantics ADR-0030 already applies to a
  Service's `spec.selector`. Keys and values are checked against Kubernetes' own
  label charset, so `app!=api` is a rejected reference rather than a key called
  `app!` that matches nothing.

- **A live pod is one that is neither terminal (`Succeeded`, `Failed`) nor
  terminating.** One rule, and it is what makes a selector safe over any owner
  kind: a completed Job's pods are never ready and would otherwise drag a node to
  `UNHEALTHY` for having finished successfully, which is ADR-0030's reason for
  refusing to discover Jobs arriving from the other direction. Terminating pods
  are excluded so a rolling update does not inflate the count; a surge pod is
  not, because it is genuinely wanted now.

- **`ready` is the pod's `Ready` condition**, which is the very thing
  `status.readyReplicas` counts on a workload. ADR-0025 rejects reading
  *`Available`* on a workload because it holds at 2 of 3 replicas; a per-pod
  `Ready` is not that condition and is not that argument. A selector over a
  Deployment's pods therefore reports the same numbers the Deployment does.

- **All / some / none ready is ADR-0025's arithmetic, unchanged**, with the live
  count in place of `spec.replicas`.

- **Zero matching pods abstains. A pod set may never say `DISABLED`.** A
  scaled-to-zero owner and a selector with a typo in it are the identical
  reading, and ADR-0029 requires evidence of a deliberate act, which nothing in
  an empty set carries. It is precisely the blindness ADR-0029 refuses to let
  `kafka` guess past with an `EMPTY` consumer group. An unparseable reference
  abstains too, and logs the string it could not read.

- **`rawSignal` reads `"2 pods / 2 ready"`** — not `"desired"`, which would claim
  a spec nobody read. **`metrics` reuses `desiredReplicas` / `readyReplicas`**
  from ADR-0028's allow-list, with the live count as `desiredReplicas`.

## Consequences

- **The Strimzi user gets the third backing back**, and so does everyone whose
  workload has an owner this plugin does not produce nodes from: a future
  operator's CRD, a DaemonSet — which ADR-0030 excludes from node emission and
  which could therefore carry no readiness at all — a bare `ReplicaSet`. One
  mechanism, no vendor vocabulary, and nothing to add when the next operator
  ships.

- **ADR-0034's "only workload backings contribute" now reads "only workload and
  `pods` backings".** Service and Ingress backings stay inert; there is still no
  kind whose readiness is invented rather than read.

- **`pods` is not a produced kind and never will be.** Discovery does not emit
  it, does not read pods, and `WorkloadKind` does not gain a constant — that enum
  is what the plugin asks the cluster for and what becomes a node. ADR-0005 is
  untouched: a pod is still not a node and still not a backing. The backing names
  a **query**, and the pods it matches are counted and discarded without one of
  them ever being named.

- **`DISABLED` does not travel through a pod set**, so ADR-0034's worked example
  — scaling the shared `kafka-connect` StatefulSet to zero makes both connectors
  `DISABLED` — has no analogue here: the same scale-down through a selector makes
  both connectors abstain, and their `connect` observation stands alone. An
  operator who wants the `DISABLED` should name the owning workload, which is
  available whenever the owner is a kind this plugin produces. That is the honest
  split: the spec is where intent lives, and a pod set is downstream of it.

- **`desiredReplicas` means *live pods* for this backing**, which is one step
  removed from the spec the name implies and can transiently exceed it during a
  rollout. Accepted over the alternatives: a third metric key would break
  ADR-0028's enumerated allow-list, and emitting no metrics would drop the
  overlay from exactly the nodes this backing exists to serve. `rawSignal` says
  `pods` in words, which is where a reader looking at one card actually reads it.

- **A selector is a poor alternate name.** ADR-0023 makes `backings[].reference`
  part of a node's identifying string set, matchable by `/`-separated segment, so
  `market-demo/strimzi.io/cluster=market` puts `strimzi.io` and
  `cluster=market` into the search index. Noise, not a hazard, and the node's own
  key and display name are unaffected.

- **Pods are listed for every configured namespace on every health poll**,
  whether or not anything carries a `pods` backing — a sixth list call, over what
  is usually a namespace's largest collection. The seam is *a namespace, per
  poll* (ADR-0012, ADR-0099), and making this one call conditional would make the
  recorded snapshot depend on the configuration being replayed. It is the same
  cost the other five already carry, and **ADR-0012 is what reopens** if listing
  a whole namespace every thirty seconds ever becomes a complaint.

- **The reference is validated nowhere but at the point of use.** It arrives as
  an opaque string in another plugin's configuration (ADR-0022), so no startup
  check can reach it and a typo costs a warning per poll and an unobserved node
  rather than a boot failure. That is the price of the contract that made this
  change cheap, and it is why the parse is strict and fails toward *no selector*
  rather than toward one matching everything.

- **This is an ADR-0102 amendment of mechanism.** ADR-0030's reasons — which
  kinds become nodes, why a Job does not, why selector matching is the only
  surviving inference — are untouched, as are ADR-0034's. What changes is that a
  `kubernetes` observation no longer has to start from an object this plugin
  lists.
