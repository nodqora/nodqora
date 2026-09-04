# ADR-0091: `kubernetes` emits no kind-derived `type`; every scalar it emits is annotated or `null`

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Unowned plugin-scope gaps: `connect` suppression and the Kubernetes type default](https://github.com/fredskor/nodqora/issues/20)
- **Amends**: ADR-0032, ADR-0044, ADR-0051

## Context

ADR-0032's annotation table says `topology.io/type` sets `type`, *"overriding
ADR-0030's kind-derived default"* — and **ADR-0030 states no such default.**
ADR-0044's contested-scalar table repeats the phrase, and ADR-0051 builds an
argument on it. `Deployment` → `service` is fixture convention
(`docs/reference-pipeline.md` §2), never a decision.

`kubernetes` is the only plugin where this is open. ADR-0038 pins `kafka` to the
constant `kafka-topic` and `connect` to the constant `connect-connector`.

## Decision

**`kubernetes` emits `type: null` unless `topology.io/type` says otherwise.
There is no kind-derived default, for any of the three kinds ADR-0030 admits.**

This makes the plugin uniform with itself. Every scalar `kubernetes` can emit is
now **annotated or `null`**:

| scalar | `kubernetes` emits |
|---|---|
| `type` | `topology.io/type`, else `null` |
| `displayName` | `null` on purpose (ADR-0034) |
| `ownerKey` | `topology.io/owner`, else `null` |
| `description` | never emitted |

That is a rule where there were three separate cases, and it ends `kubernetes`
being the plugin with an exception.

### Why no default rather than a better default

**`kind` is a deployment mechanism, not a component role.** ADR-0005 makes the
graph logical and physical objects backings. `kafka` and `connect` can emit
constants honestly because the technology object and the logical component
coincide — a Kafka topic *is* a topic. For Kubernetes they do not: a StatefulSet
is a database as often as a service, a Deployment is a gateway or a worker or a
service. ADR-0032 already found this from the other end when it renamed
`topology.io/service` to `topology.io/node`, *"because the key names the node,
not a type"*.

**It is ADR-0034's own argument.** `displayName` is emitted `null` *"not to
resolve a merge conflict with YAML but to avoid manufacturing one."* A
kind-derived `service` colliding with a YAML-declared `gateway` is a
manufactured conflict, and ADR-0051 is explicit that the collision detector
**cannot** report it.

**It is structurally free.** ADR-0048's stub node already establishes the
typeless case — *"no `type`, so ADR-0001's fallback descriptor renders it"* — so
this needs no new rendering path and no empty state ADR-0019 has not designed.

**A guessed default pollutes the registry.** Under ADR-0080 whatever a plugin
emits registers a TypeDescriptor as a read-time projection, so a kind-derived
default would put types nobody chose into the global set served inside `/graph`.

The rejected concrete alternatives:

| option | why not |
|---|---|
| `Deployment`/`StatefulSet` → `service`, `CronJob` → `job` | a guess, and `service` for a StatefulSet is likeliest wrong — StatefulSets are databases and brokers |
| lowercased kind: `deployment`, `statefulset`, `cronjob` | honest but useless: it restates the backing's own `kind`, so the inspector prints it twice — and it puts a Kubernetes noun in a core field, which is what ADR-0009 dropped `namespace` for |

## Consequences

- **An unannotated Deployment has no type at all** and renders with ADR-0001's
  fallback descriptor. This is the cost, and it is priced deliberately: it is
  honest, because nobody has said what the thing is, and `type` is already one
  of ADR-0032's nine keys for exactly this purpose.
- **`docs/reference-pipeline.md` §5 is amended** — `topology.io/type: service`
  joins the annotation block on both Deployments. Without it the §2 `type`
  column stops being true. This is the third amendment ADR-0032 has forced on
  the fixture, after `topology.io/service` → `node` and the addition of
  `consumer-groups`; the pattern is that the fixture was written as
  documentation and keeps turning out to be under-specified where a plugin's
  output is concerned.
- **Declaring the type in YAML instead is unavailable.** ADR-0063 uses
  `type: service` on `payments-api` as its worked example of the anti-pattern —
  a field that *"looks like documentation and behaves like a veto over whatever
  `kubernetes` reports forever."* The annotation is the route precisely because
  it sits on the object rather than above it in precedence.
- **ADR-0051's rationale is amended; its decision stands.** It rejected "two
  snapshots disagree about `type`" as a collision detector by citing *"ADR-0032
  has `topology.io/type` override a kind-derived default, so YAML calling a
  StatefulSet `service` is a legitimate disagreement settled by precedence."*
  That example no longer exists — a `kubernetes` type is now always an explicit
  human annotation, so a YAML/annotation disagreement is two people contradicting
  each other on purpose rather than a human correcting a machine's guess. The
  conclusion is unchanged and reached by the shorter route ADR-0051 already
  gives: `yaml` plus one code plugin is the *designed* case, so it stays a log
  and a metric, never a model field and never `PARTIAL`.
- **ADR-0044's precedence order is untouched but does less work.** With
  `kubernetes` contributing `null` unless annotated, `type` is contested only
  when a YAML stanza and an annotation both speak. The order still settles it —
  `yaml` wins — and that remains the §56 override mechanism.
- **Nothing downstream branches on a missing type.** ADR-0016's layout is over
  edges, ADR-0017's health encoding is over `health`, and ADR-0067 declined to
  widen the search field set to `type`. The inspector is the only surface that
  shows it.
