# ADR-0106: A blind observer's last reading is carried forward, so the caveat names staleness rather than absence

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Slice 5 — The honesty layer](https://github.com/fredskor/nodqora/issues/26)
- **Amends**: ADR-0083, ADR-0088

## Context

ADR-0083 fixed the inspector caveat for a blind observer, and wrote the sentence
out:

> `connect` could not observe this node this cycle. Healthy is composed from
> kubernetes + kafka only.

Built against the running application, **that sentence is false.** ADR-0072 gave
the fast half a store and settled its transitions in one line: *"contributions
are **retained per plugin** under ADR-0026's three outcomes, by the same rules
ADR-0046 gives discovery."* A `FAILED` health run is therefore a **no-op on
`health_contribution`**, exactly as it is on `plugin_snapshot_entry` — the
behaviour `ContributionStoreTest` has asserted since slice 3 under the name *a
failed run leaves the last reading standing rather than blanking it*.

So when the Connect cluster goes dark, `payments-es-sink` does not compose down
to two observers. It composes from three, one of which is a reading from before
the outage. `/state` says so in its own words:

```text
"2 desired / 2 ready; lag 120; RUNNING, 3/3 tasks RUNNING"
```

The `connect` segment is still there.

ADR-0083 was written at ticket #17, twelve ADRs before the fast half had a store
at all, in a world where a plugin that did not answer contributed nothing. The
sentence was correct about that world.

## Decision

**The mark, the mechanism selecting it, and the reason behind it are all
unchanged.** A blind node's health is under-observed and its topology is
current; that is still a different statement from a retained node's, and it still
earns a different mark. What changes is one sentence.

**The caveat is selected by two facts already on the wire**, neither of them a
new field:

| the plugin's `recordedAt` | the node's `observedAt` | caveat |
|---|---|---|
| `null` — never polled | either | *"`connect` has not observed this node yet. The value above is composed from kubernetes + kafka only."* |
| set — polled | `null` — nothing has ever observed this node | *"`connect` could not observe this node this cycle. Nothing else observes this node."* |
| set — polled | set — there is a reading here | *"`connect` could not observe this node this cycle. A failed poll does not clear what it last reported, so the value above may include a reading nobody has re-checked."* |

Row one is ADR-0083's original sentence, which turns out to be exactly right for
the case ADR-0088 added and wrong for the case ADR-0083 wrote it about.

**Row two exists because `recordedAt` says *polled*, not *succeeded*.** ADR-0086
writes the header on a failed poll, so a plugin that has never once succeeded
still has a `recordedAt`, and reading it as "has a stored reading" produces a
sentence about a retained value directly beneath a Health section reading
`Raw signal: nothing observes this node` and `Observed: never`. That is the
"sentence with no referent" ADR-0104 removed from `observedAt` itself, reappearing
one line lower. The node's own `observedAt` settles it: with no observation at
all, nothing was carried forward by anyone.

**Row three states the mechanism rather than asserting a reading.** The frontend
cannot know whether the *blind* plugin in particular has a stored contribution —
`observedAt` is the `min` over all of them and ADR-0057 exposes none
individually — so the sentence says what a failed poll does and lets the reader
draw the conclusion. "May include" is a statement about the rule, not a hedge
retracting a headline: it is true when a reading exists and true when one does
not.

## Consequences

- **`observedAt` was already telling the truth, and now the sentence agrees with
  it.** ADR-0072 made a composed `observedAt` the **`min`** over contributions
  precisely so that *"a plugin which could not look must not let the others make
  a node look fine"* — a `max` would let a five-second `kubernetes` observation
  present a `kafka` reading from three cycles ago as current. Under an outage the
  retained `connect` contribution drags the node's freshness backwards while its
  unaffected neighbours stay current, so the drawer's "Observed" row goes stale on
  exactly the marked nodes. That is a second, independent channel saying the same
  thing, and it was designed in before anything needed it.
- **ADR-0088's "its inspector sentence needs no rewording" is the sentence
  amended.** That ADR folded the unreported case onto the blind mark and checked
  the wording against ADR-0083's; both were checked against a world without a
  contribution store. The mark stays shared — a third canvas encoding is still
  rejected against ADR-0081's conceded cost — and only the prose splits.
- **This makes the blind mark *more* load-bearing, not less.** ADR-0026's failure
  mode was *"green because we stopped looking"*. The real behaviour is worse than
  the one it described: the node is green **partly on evidence that is no longer
  current**, and neither the health glyph nor the raw signal says so. Nothing on
  the health channel can, because ADR-0024 discards votes rather than ageing them.
- **Retention is the right transition and is not reopened.** The alternative —
  clearing a plugin's contributions when its poll fails — is ADR-0046's rejected
  case one level down: it makes a Connect blip delete a real reading, and under
  ADR-0104 a node whose only observer blipped would lose its row entirely and flip
  to `UNKNOWN` for a cycle. ADR-0026 rejected exactly that: *"it lets one Connect
  blip erase a genuine Kubernetes signal."*
- **Cost:** the caveat cannot say *how* stale a carried reading is, or whose it
  was. Naming either would need per-plugin `observedAt` on the wire, which is
  `contributions[]` by another name — refused by ADR-0057, which designed **one**
  Health section rather than a per-plugin table. The composed `observedAt` above it
  is the available number and it is a lower bound, which is the safe direction.
- **This was found by running the application, not by reading the ADRs.** The first
  sentence was written from ADR-0072's transition table and looked right; it was
  false on the first node it rendered against, because the deployment it rendered
  against had never had a successful Kafka poll at all. ADR-0099's recordings cannot
  produce that state — every one of them answers — which is a narrow gap in the
  fixture worth naming: it records what a cluster *says*, never a cluster that has
  never spoken.
- **This is an ADR-0102 amendment of mechanism.** ADR-0083's reason — retained and
  blind are different problems and the caveat belongs inside Health — is untouched.
  A sentence was falsified by a store that did not exist when it was written.
