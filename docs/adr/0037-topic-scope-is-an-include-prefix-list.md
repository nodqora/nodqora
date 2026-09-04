# ADR-0037: Topic scope is a required include-prefix list with exact-name suppression

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

## Context

ADR-0036 makes topics nodes; `listTopics` returns every topic on the cluster.
On a shared cluster that is hundreds of topics belonging to a dozen teams, all
of which would land on the payments canvas.

This is ADR-0031's eleventh-node problem again, and it is worse. `payments-prod`
is a namespace: few, stable, and already how humans partition a cluster, which
is what let ADR-0035 call it "the one narrowing knob that cannot be avoided" and
enumerate it. **A Kafka cluster has no such partition.** "All topics" is the
cluster-wide mode ADR-0035 rejected, for a technology with no namespace to fall
back on.

## Decision

```yaml
kafka:
  topics:
    include: [payments.]                    # required, non-empty, no empty-string element
    ignore: [connect-offsets]               # exact names (ADR-0031)
```

**Scope is an ordered list of topic-name prefixes, matched exactly on a leading
substring. No regex, no glob.** Kafka's only organising convention is the dotted
name — `payments.events.raw.v1` — and a prefix is the closest thing Kafka has to
a namespace. New topics under an owned prefix appear on their own, which is what
distinguishes discovery from enumeration.

Rejected alternatives:

| option | why not |
|---|---|
| all topics, minus internal | subtractive, which is ADR-0031's preferred failure direction — but that argument assumes the wrong-extras are *countable*, and `ignore` removes them one exact name at a time |
| enumerated topic names, like ADR-0035's namespaces | topics are not namespaces: they are created constantly, so a new pipeline topic is silently missing. Discovery that requires you to name what you are discovering is not discovery |
| only topics some other plugin or YAML already named | breaks ADR-0012 outright — results are full snapshots of the plugin's own scope, produced in parallel with no cross-plugin ordering, so `kafka` cannot see the merged graph at emit time |

**`listInternal=false` is set but is not sufficient.** It hides
`__consumer_offsets` and `__transaction_state`, but Connect's `connect-configs`
/ `connect-offsets` / `connect-status` are ordinary topics as far as Kafka is
concerned. The `ignore` list is what removes them.

## Consequences

- **This is narrowing, adopted anyway, mitigated the way ADR-0035 mitigated
  namespaces**: no default, no wildcard, no empty-string element, startup
  failure on empty. A forgotten prefix reads as "the whole pipeline is gone",
  not as one subtly absent topic.
- **A topic named outside the convention is silently absent.** That is the
  residual cost, and it is the reason ADR-0042 makes a zero-match prefix a
  named `PARTIAL` reason rather than a silent success.
- **Prefix matching is not a fuzzy tier.** ADR-0021 banned fuzziness in
  *identity resolution* — which node an object belongs to. This is scope
  selection, a different job, and the match is still exact.
- **This shape is reused for connectors.** ADR-0090 adopts the required
  include-prefix list for `connect` on the strength of this ADR's own rejection
  of enumerated names — which turned on topics being *created constantly*, a
  property connectors do not have.
- **The scope decision pays for the cadence decision.** ADR-0042 puts
  `describeConfigs` on the 5-minute loop; research #5 warned its response is
  very large over *every* topic on a cluster. Prefix scope is what keeps it
  small.
