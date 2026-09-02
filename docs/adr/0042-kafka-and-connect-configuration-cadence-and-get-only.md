# ADR-0042: Kafka and Connect configuration, cadence, outcome semantics, and GET-only enforcement

- **Status**: Amended by [ADR-0047](0047-deletion-is-immediate-and-the-zero-output-guard-is-a-plugin-obligation.md)
- **Date**: 2026-09-02
- **Ticket**: [Kafka and Kafka Connect discovery scope](https://github.com/fredskor/nodqora/issues/11)

> **Amendment (ADR-0047).** The zero-output guard generalizes to all three code
> plugins: **an enumerated scope unit that yields zero nodes ⇒ `PARTIAL`**.
> `connect` gains the reason it lacked — a configured cluster with zero connectors —
> and `kubernetes` gains the namespace equivalent (ADR-0035). `kafka`'s two reasons
> below are unchanged, and the undetectable ACL residual handed to #12 is answered
> there: it stays undetectable, and ADR-0047 accepts it rather than guarding it.


## Context

ADR-0014 made plugin configuration file-declared and per-environment with
secrets as references; ADR-0012 fixed two cadence loops and an
`outcome` of `COMPLETE | PARTIAL(reasons[]) | FAILED(cause)`, handing the
residual "COMPLETE but silently blind" ambiguity to
[#12](https://github.com/fredskor/nodqora/issues/12) *labelled*. ADR-0035 set
`kubernetes` to 5m/30s and explicitly left these numbers open.

Research #5 established the two plugins fail in **opposite** directions.
Kafka fails silently: `listTopics`, `listGroups` and friends return empty or
filtered results on an authz failure rather than raising. Connect fails loudly,
with HTTP status codes. That is the exact inverse of their credential stories —
Kafka has a clean read-only principal and lies about failures; Connect has no
read-only mode at all and tells the truth.

## Decision

### Configuration

```yaml
kafka:
  bootstrap: kafka-prod.internal:9092              # required
  properties:                                      # passthrough to AdminClient
    security.protocol: SASL_SSL
    sasl.mechanism: SCRAM-SHA-512
    sasl.jaas.config: "${env:KAFKA_PROD_JAAS}"
  topics:
    include: [payments.]                           # ADR-0037
    ignore: [connect-offsets]
  lag:
    default: 10000                                 # required
    groups:
      enrich-consumer-prod: 10000                  # optional override (ADR-0025)
  links: { ... }                                   # ADR-0039
  discoveryInterval: 5m
  healthInterval: 30s
  discoveryTimeout: 30s
  healthTimeout: 10s

connect:
  url: https://connect-prod.internal:8083          # required
  auth: { username: nodqora, password: "${env:CONNECT_PROD_PASSWORD}" }   # optional
  workload:                                        # optional (ADR-0022)
    plugin: kubernetes
    kind: statefulset
    reference: payments-prod/kafka-connect
  links: { ... }
  discoveryInterval: 5m
  healthInterval: 30s
```

**`properties` is an untyped passthrough, and that is not a hole in ADR-0014.**
Kafka security configuration is open-ended — SASL mechanisms, SSL stores, OAuth
callbacks, each with its own keys — so a typed class cannot enumerate it. The
distinction that keeps this consistent: **allow-listing governs what leaves the
plugin, not what enters it.** ADR-0014 banned everything-except-`*.password` for
*output*, because that is what reaches the API and the canvas. Operator-authored
input has to be open, and `${env:}` / `${file:}` keeps secrets out of the file.

**`workload` is optional.** A Connect cluster not on Kubernetes — MSK Connect,
bare metal, Docker — is normal, and requiring it would force operators to invent
a reference. Absent, connectors carry two backings instead of three and lose the
readiness contribution.

**`lag.default` is required, non-negative.** The alternative is a built-in
number, which turns nodes DEGRADED on the canvas because of a threshold nobody
chose. Requiring it once means every amber node traces to a human decision —
ADR-0035's "make it loud" applied to a number rather than a scope.

Startup validation, failing fast per ADR-0014: `bootstrap` and `url` present;
`topics.include` non-empty with no empty-string element; `lag.default` present
and non-negative; every timeout strictly below its interval, so two polls can
never be in flight.

### Cadence

| plugin | loop | calls | interval |
|---|---|---|---|
| `kafka` | health | `describeConsumerGroups` + `listConsumerGroupOffsets` over routed groups (per-coordinator batched), `listOffsets(latest)` for in-scope partitions (per-leader batched) | 30s |
| `kafka` | discovery | `listTopics`, `describeTopics`, `describeConfigs` | 5m |
| `connect` | health | one `GET /connectors?expand=status` | 30s |
| `connect` | discovery | one `GET /connectors?expand=info` | 5m |

The same numbers as ADR-0035, reached independently rather than inherited: once
ADR-0040 makes the group set the *routed* set rather than the cluster's, every
fast-loop call is batched and bounded, which is what research #5's cost table
calls "cheap enough for 30 s". Connect's health is literally one HTTP request,
resolved in-process on the receiving worker.

**Research #5's three-tier schedule (30s / 5m / 15m) is unavailable**, because
ADR-0012 fixed two loops — and unnecessary: `describeConfigs` batches every
topic into one request, and ADR-0037's prefix scope keeps the response small.

`expand=info` returns unmasked connector configs, so it sits on the 5-minute
loop rather than the 30-second one: twelve exposures an hour instead of a
hundred and twenty. Not a mitigation, but not nothing.

### Outcome

**`kafka` gets two named `PARTIAL` reasons**, covering the two places blindness
is detectable:

1. **A configured `include` prefix matching zero topics**, naming the prefix. A
   declared prefix is a declared expectation; zero matches is either an ACL
   failure or a wrong prefix, and both deserve to be loud. ADR-0035's namespace
   mitigation applied unchanged: the ambiguity cannot be removed, so it shouts
   rather than shrugs.
2. **A topic returned by `listTopics` whose `describeConfigs` came back empty**,
   naming the missing `DescribeConfigs` ACL. Unlike (1) this is unambiguous: we
   have the topic, so we are authorized to see it, and empty configs can only
   mean the second, distinct ACL is missing (ADR-0038).

**`connect` reports HTTP truthfully**: an error on `GET /connectors` is
`FAILED(cause)`; a per-connector call failing while the list succeeded is
`PARTIAL` naming that connector.

### The `connect` plugin issues only `GET`, ever

Research #5 verified there is no read-only Connect credential and no
per-endpoint authorization: whatever credential Nodqora holds can delete every
connector, and the only real mitigation is an operator-side GET-only reverse
proxy. Connect cannot be fixed from here, but Nodqora can be made provably
harmless against it — **enforced as an ArchUnit rule that fails the build**, the
same move ADR-0010/ADR-0015 made with the §5.2 boundary, discharging §5.6
("read-only by default").

## Consequences

- **The GET-only rule protects against us, not against a stolen credential**,
  and is not claimed to do more. The reverse-proxy recommendation and the
  unmasked-config warning (ADR-0039) are operator-facing deployment notes the
  MVP must not leave implicit.
- **An ACL tightening that hides *some* topics under a prefix that still matches
  others remains invisible**, and will read as a decommissioned pipeline. That
  is #12's to survive, not this ticket's to prevent, and it is why #12 cannot
  treat absence as deletion.
- Kafka needs only a `Describe` / `DescribeConfigs` principal; ADR-0038 dropped
  `Describe` on Cluster by never calling `describeLogDirs`.
- Both plugins land on ADR-0003's boundary without strain, which is a second
  confirmation that the Node/NodeState split survives contact with real adapters.
