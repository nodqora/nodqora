# Running against your own cluster

The shipped configuration points at the reference pipeline: fictional hosts, a demo topology, a
canvas that renders without a cluster anywhere near it. This is how you point Nodqora at
infrastructure you actually run.

It is two files, and the interesting part is which one holds what.

## The division of labour

Nodqora never asks one source for the whole picture. Each plugin contributes what it can actually
know, and [the fold](../CONTEXT.md) merges the contributions into one node.

| | knows | cannot know |
|---|---|---|
| `kubernetes` | that a workload exists, its desired and ready replicas, its Services and Ingresses | **who owns it**, and **what talks to what** — it emits no edges at all (ADR-0033) |
| `yaml` | edges, ownership, anything you are willing to write down | anything live |

So you write down the gaps and let the cluster supply the rest. ADR-0063 states this as **declare
the gaps, not the graph**, and it is a rule with teeth: `yaml` wins every contested scalar, so a
field written there that a plugin also knows is a silent, permanent override of the live value.

## File 1 — the configuration

One environment, and the plugins that observe it:

```yaml
# local.yaml
nodqora:
  refresh:
    discovery: 30s      # the shipped cadence is 5m; 30s while you are iterating
    health: 30s
  environments:
    homelab:
      display-name: Homelab
      plugins:
        yaml: { dir: /absolute/path/to/your/topology-dir }
        kubernetes:
          namespaces: [n8n, monitoring, actual]
```

Layer it on top of the packaged configuration rather than editing that file:

```bash
./gradlew :nodqora-app:bootRun --args="--spring.config.additional-location=file:./local.yaml"
```

Environments merge by key, so `homelab` appears in the switcher alongside the demo `production` and
`staging`. Omitting a plugin block is how you scope an environment — an environment with no `kafka:`
block simply has no Kafka, and reports nothing about one.

### What the `kubernetes` block needs

**Only `namespaces`**, which is required and must be non-empty. There is deliberately no wildcard
and no `all` mode: a forgotten namespace should read as *half my graph is gone* rather than as one
subtly absent node, which is indistinguishable from real drift (ADR-0035).

Everything else is optional:

| key | effect |
|---|---|
| `kubeconfig` | the kubeconfig **contents**, not a path — pair it with `${file:...}` below, which has already read the mounted file by the time config is bound. **Absent means in-cluster**, falling back to your ambient kubeconfig when you are not running in one |
| `context` | a named context within that kubeconfig |
| `ignore` | exact `kind/name` entries, no globs — suppresses *node emission* only, so the object can still be stamped as a backing |
| `links` | URL templates, composed here rather than stored, so one manifest renders correctly in every environment (ADR-0032) |

Running on your laptop with no `kubeconfig` line, the plugin uses `~/.kube/config`. If that file has
expired credentials, every namespace listing fails — see [Troubleshooting](#troubleshooting).

## File 2 — the topology

Every `*.yaml` in the configured `dir` is read together as one snapshot (ADR-0061), so split it by
team or by stack however you like.

```yaml
environment: homelab

owners:
  - key: automation
    displayName: Automation
    channel: "#homelab"
    onCall: Fyodor

nodes:
  - key: cloudflared
    owner: automation
    calls: [n8n]

  - key: n8n
    owner: automation
    calls: [postgres]

  - key: postgres
    owner: automation
```

Six relation verbs:

| written as | stored as |
|---|---|
| `calls`, `producesTo`, `writesTo` | the same direction |
| `consumesFrom`, `sourcesFrom`, `queries` | **reversed** |

The last three read backwards on purpose. You write them from the consumer's side, where the fact
lives, and every edge is stored flow-directed — so an arrow on the canvas always points the way data
moves, and nobody has to remember which relations run against the flow (ADR-0002).

## The join, which is the part that actually matters

**A node's `key` must equal the Kubernetes object's name.**

Identity resolves in two tiers and there is no third: the `topology.io/node` annotation if the object
carries one, otherwise the object's own name. So `postgres` in your topology file merges onto the
`postgres` StatefulSet, and the resulting node carries `sources: [yaml, kubernetes]`.

Get it wrong and **nothing errors**. You get two nodes sitting side by side instead of one merged
node, which looks like a rendering quirk and is really a spelling mistake. So list the names first:

```bash
kubectl -n <namespace> get deploy,sts,cronjob \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
```

That command is also the answer to *what will become a node*:

- **Deployments, StatefulSets and CronJobs** become nodes.
- **DaemonSets do not** — infrastructure-shaped, and a bare Job is a *run* rather than a component
  (ADR-0030).
- **Services and Ingresses are not nodes.** They attach to a node as *backings*, which is why a
  workload with a Service and an Ingress shows three backings and one node.

## Annotations: the same thing, without file 2

Everything the topology file declares about a *node* can instead live on the workload, which is the
better answer for a real deployment because it travels with your manifests:

```yaml
metadata:
  annotations:
    topology.io/node: postgres          # overrides identity — tier 1
    topology.io/type: database          # otherwise the card reads "Unknown type"
    topology.io/owner: automation
    topology.io/consumer-groups: "orders-consumer,orders-retry"   # comma-separated; plural
    topology.io/repository: https://github.com/acme/thing
    topology.io/runbook: https://wiki/runbooks/thing
    topology.io/docs: https://docs.acme.io/thing
    topology.io/grafana: thing-overview
    topology.io/ignore: "true"
```

`argocd.argoproj.io/instance` is read too, with no configuration.

**The vocabulary is closed** (ADR-0032). Any other `topology.io/*` key is ignored, and logged as
`carries unrecognised annotation '...'` — so a misspelling costs you a warning in the log rather than
a field that silently never arrives. `topology.io/ignore` is matched against exactly `"true"` and
nothing else, deliberately: an unrecognised value leaves the node on the graph, which is visible,
rather than removing it, which reads as drift.

What annotations **cannot** give you is edges. Those come from `yaml`, or from `kafka` and `connect`
observing real topics and connectors. A cluster-only environment is a grid of disconnected cards —
correct, and not very useful.

## What you should see

Within one discovery cadence:

```bash
curl -s localhost:8080/api/environments/homelab/graph | python3 -c 'import json,sys; g=json.load(sys.stdin); print(len(g["nodes"]), "nodes,", len(g["edges"]), "edges,", [(p["plugin"], p["outcome"]) for p in g["plugins"]])'
```

```text
9 nodes, 6 edges, [('yaml', 'COMPLETE'), ('kubernetes', 'COMPLETE')]
```

`kubernetes DISCOVERY -> COMPLETE`, and every node you declared carrying both sources. Then within
one health cadence, `/state` fills in:

```
HEALTHY   n8n          1 desired / 1 ready
HEALTHY   cloudflared  2 desired / 2 ready
```

**A cold start shows one cycle of grey.** The first health poll can land before discovery has routed
the backings, so every node reads `UNKNOWN` until the next cycle. That is the honest answer — nothing
had been observed yet — not a fault.

To see a state that is not green, scale something to zero:

```bash
kubectl -n <ns> scale deploy/<name> --replicas=0
```

It reads `DISABLED`, raw signal `scaled to 0` — a pause glyph rather than a red dot. Intent here is
*declarative*: it is read from `spec.replicas`, written by a human or an autoscaler acting on one,
rather than inferred from an absence of activity (ADR-0029). Nobody is asking this to run is not the
same fact as this is broken.

## Troubleshooting

**Every namespace fails with `JcaPEMKeyConverter is provided by BouncyCastle`.**
Your cluster's CA issues EC rather than RSA client certificates — k3s does. Fixed in #33 by declaring
BouncyCastle; if you are on an older build, that is the cause. The message names a *namespace*, so it
reads like an RBAC or connectivity fault. It is neither.

**Every namespace fails with `the server has asked for the client to provide credentials`.**
Expired client certificate in the kubeconfig being used. Check it:

```bash
grep -m1 client-certificate-data ~/.kube/config | awk '{print $2}' |
  base64 -d | openssl x509 -noout -dates
```

**The graph is empty but `plugins[]` says `COMPLETE`.**
The namespaces hold no Deployment, StatefulSet or CronJob. DaemonSet-only namespaces produce no
nodes by design.

**Your declared nodes and the observed workloads appear as separate cards.**
The `key` does not match the object name. See [the join](#the-join-which-is-the-part-that-actually-matters).

**An edit to the topology file does nothing.**
Discovery runs on a cadence and there is no refresh endpoint (ADR-0053). Wait a cadence, or shorten
`nodqora.refresh.discovery`.

## Credentials

Secrets are **references, never values**, resolved at use time and never persisted (ADR-0014):

```yaml
password: "${env:KAFKA_PASSWORD}"
kubeconfig: "${file:/etc/nodqora/kubeconfig}"
```

This is how the product deploys — a ConfigMap plus a mounted Secret — and Vault or a cloud secrets
manager slots in behind the same syntax without touching a plugin.
