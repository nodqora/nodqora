# Running against your own cluster

A fresh install has no environments — [ADR-0152](adr/0152-the-image-ships-no-environments-and-config-arrives-as-two-mounts.md)
puts none in the image at all, because a baked-in environment could be overridden by your
configuration but never removed. So the canvas opens saying so, and this is how you fill it: point
Nodqora at infrastructure you actually run.

It is two files, and the interesting part is which one holds what. If you have not installed yet,
[docs/install.md](install.md) is the four-step route that gets you to the screen this page answers.


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

One environment, and the plugins that observe it. It goes in the config directory beside your
`compose.yaml`, which the shipped `compose.yaml` mounts at `/app/config`:

```yaml
# ./config/application.yaml
nodqora:
  refresh:
    discovery: 30s      # the shipped cadence is 5m; 30s while you are iterating
    health: 30s
  environments:
    homelab:
      display-name: Homelab
      plugins:
        yaml: { dir: /etc/nodqora/topology/homelab }
        kubernetes:
          namespaces: [n8n, monitoring, actual]
```

```bash
docker compose restart nodqora
```

**No flag names that file, and none needs to.** `/app/config/` is Spring Boot's own default search
location, so the file is found and **merged** with the `application.yaml` inside the jar rather than
replacing it. That is why yours carries `nodqora.environments` and, if you want them, the refresh
cadences — the two plugin orders, and the Jackson, problemdetails and cache settings all inherit.
Restating them is not required and is a way to drift.

The `yaml` plugin's `dir` is a path **inside the container**. `compose.yaml` mounts `./topology`
read-only at `/etc/nodqora/topology`, one directory per environment
([ADR-0061](adr/0061-yaml-is-a-directory-per-environment.md)), so `./topology/homelab/*.yaml` beside
your compose file arrives as `/etc/nodqora/topology/homelab` above. It is a separate mount from the
config on purpose: Spring searches `config/` and one level of `config/*/` for `application.yaml`, so
a topology file with that name under `/app/config` would be read as configuration by Spring and as
topology by the plugin.

Environments merge by key, so a second key beside `homelab` puts both in the switcher. Omitting a
plugin block is how you scope an environment — an environment with no `kafka:` block simply has no
Kafka, and reports nothing about one.

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

**In a container there is no ambient kubeconfig**, so unless Nodqora is running *inside* the cluster
it observes, this key is required. Mount the file and reference it — the value is the kubeconfig's
contents, and `${file:...}` has already read the mount by the time config is bound
([ADR-0014](adr/0014-file-declared-plugin-config.md)):

```yaml
# compose.yaml, on the `nodqora` service
volumes:
  - ~/.kube/config:/etc/nodqora/kubeconfig:ro
```

```yaml
# ./config/application.yaml
kubernetes:
  namespaces: [n8n, monitoring, actual]
  kubeconfig: "${file:/etc/nodqora/kubeconfig}"
```

Two things that mount does not solve. A kubeconfig naming `127.0.0.1` or `localhost` points at the
*container*, so the server address has to be one the container can reach — a LAN address, or
`host.docker.internal` on Docker Desktop. And a config whose credentials are an `exec` plugin (`aws
eks get-token`, `gke-gcloud-auth-plugin`) needs that binary in the image, which
[ADR-0154](adr/0154-third-party-covers-what-the-build-adds-and-the-base-image-attributes-itself.md)
keeps out of it; use a long-lived ServiceAccount token instead.

Running from a clone on your laptop with no `kubeconfig` line, the plugin uses `~/.kube/config`
directly. If that file has expired credentials, every namespace listing fails — see
[Troubleshooting](#troubleshooting).

### From source

Running from a clone rather than the image, the same file is layered on with a flag:

```bash
./gradlew :nodqora-app:bootRun --args="--spring.config.additional-location=file:./local.yaml"
```

**This replaces the demo rather than joining it.** `bootRun` sets that same property to point at
`fixtures/reference-pipeline/application-demo.yaml`, and a command-line argument outranks it, so
`homelab` is the whole roster — which is usually what you want here. Name both, comma-separated, if
you want the demo in the switcher too:

```bash
./gradlew :nodqora-app:bootRun --args="--spring.config.additional-location=file:./fixtures/reference-pipeline/application-demo.yaml,file:./local.yaml"
```

The demo's own `yaml: { dir: ... }` lines are repo-relative — both Gradle tasks pin `workingDir` to
the repo root to make that work — so a `dir` written for a clone is not the one written for a
container. That file is also the fullest worked example there is: four plugins, two environments,
and the whole `nodqora-app` suite binds it, so it cannot go stale without the build going red.


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

### If you run Kafka through Strimzi, name a selector rather than a workload

Strimzi has not used StatefulSets since 0.35. Brokers and Connect workers are both `StrimziPodSet`,
a CRD of its own, and that is not one of the three kinds above — so no node, and no workload for
`connect.workload` to name:

```bash
$ kubectl -n <namespace> get sts,strimzipodsets
No resources found of kind StatefulSet.
NAME                                      PODS  READY PODS  CURRENT PODS
strimzipodset.core.strimzi.io/my-cluster  3     3           3
```

For the **broker** that costs nothing — a broker is not a node in this model, topics are (ADR-0036).
For the **Connect worker** it used to cost you the third backing on every connector. It no longer
does: point `connect.workload` at a **label selector** instead of an object, and readiness is counted
from the pods that match (ADR-0148).

```yaml
connect:
  workload:
    plugin: kubernetes
    kind: pods
    reference: my-namespace/strimzi.io/cluster=my-cluster,strimzi.io/kind=KafkaConnect
```

The reference is `<namespace>/<key>=<value>[,...]`, split at the **first** slash and nowhere else —
label keys are usually DNS subdomains, so the rest of the slashes belong to the selector. Entries are
AND-ed and matched by equality only; `!=`, `in` and bare-key existence are not part of the grammar
and are rejected rather than silently matched. Check yours against the same pods Nodqora will count:

```bash
kubectl -n <namespace> get pods -l strimzi.io/cluster=my-cluster,strimzi.io/kind=KafkaConnect
```

Connectors then carry all three backings and read `2 pods / 2 ready` where a Deployment-hosted
cluster reads `2 desired / 2 ready`. Nothing about this is Strimzi-specific — the same line works for
any operator's CRD, for a DaemonSet, or for anything else whose owner is not a kind `kubernetes`
produces nodes from.

Two things a selector cannot do that naming a workload can:

- **It never reports `DISABLED`.** No matching pods is the same reading whether the owner was scaled
  to zero or your selector has a typo in it, and `DISABLED` is reserved for evidence of a deliberate
  act (ADR-0029). A pod set that matches nothing abstains, exactly as an object that has been deleted
  does. If your Connect cluster *is* a Deployment or StatefulSet, name it — the spec is where intent
  lives.
- **It is not checked at startup.** The reference is an opaque string to the `connect` plugin
  (ADR-0022), so a malformed one costs you a log line — `pods backing '...' is not a
  <namespace>/<key>=<value>[,...] selector` — and an unobserved node, rather than a boot failure.

Omitting the `workload` block entirely is still fine, and still the right answer for a Connect
cluster that is not on Kubernetes at all — MSK Connect, bare metal, Docker. Connectors then carry two
backings and no readiness contribution: you lose the `2 pods / 2 ready` half of the raw signal, and
`RUNNING, 3/3 tasks RUNNING` still arrives from `connect` itself.

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
