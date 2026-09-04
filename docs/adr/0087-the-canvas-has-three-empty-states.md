# ADR-0087: The canvas has three empty states, and none of them promises a time

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [The empty and repopulating environment](https://github.com/fredskor/nodqora/issues/19)
- **Amends**: ADR-0018

## Context

ADR-0070 designed the *search* empty state and gave the reason: *"'No results'
and 'no such node anywhere' otherwise render as the same pixels, and an
undesigned empty state discards the scope information for free."* The **canvas**
empty state was never specified for anything but "this environment has no
nodes".

ADR-0085 puts the missing fact on the wire. ADR-0055 keeps the environment
roster in static meta from config, so a cold `production` still appears in the
switcher and can be opened — which is what makes an empty canvas reachable at
all.

## Decision

Three empty states, selected by `plugins[]` alone:

| condition | rendering |
|---|---|
| roster is empty | **"No plugins are configured for `production`."** |
| no discovery-capable pair has reported | **"`production` has not been read yet."** |
| otherwise | **"No nodes in `production`."** |

**None of them states or implies a time.** No countdown, no "back in about five
minutes".

**A `payload_version` discard renders identically to a fresh deployment.**
Nothing distinguishes them and nothing tries to.

## Consequences

- **Leaving this to ADR-0081's chip alone was rejected on ADR-0081's own
  grounds.** It refused environment-level-only *"because it leaves the canvas
  with nothing in the blind case"* and declined to license *"the canvas being the
  last place to find out"*. A cold graph is the maximal instance: ADR-0083's
  marks were designed for a few affected nodes, and here every node is affected
  and there is not one on screen to mark.
- **A single state with an explanatory subline was the runner-up and is
  dishonest.** "No nodes in `production`" is a **false statement** during a cold
  read, and a subline does not retract a headline.
- **No countdown, for ADR-0084's reason.** ADR-0059 publishes
  `refresh.graphSeconds` as the **minimum** over configured plugin cadences, so a
  slower plugin arrives long after it elapses. Promising a time from that number
  is the dishonest threshold ADR-0084 refused when it declined a staleness TTL —
  *"a plugin polling at fifteen minutes would be marked stale at ten, on a healthy
  poll"*, restated as a broken promise instead of a false mark.
- **The two ways in converge deliberately.** ADR-0080's discard removes headers
  and entries together on one application-wide constant, so a post-discard
  environment is byte-identical to a fresh one. Distinguishing them would need a
  marker that **survives its own version bump** — a row whose shape must stay
  readable across every future version, which is *"tolerant deserialization
  forever"*, rejected by ADR-0080 for *"having no forcing function"*, smuggled
  back as one field. The remedy is identical in both cases (wait one cadence), so
  the distinction would change no action.
- **Cost:** an SRE opening `production` thirty seconds after somebody else's
  deploy sees a fresh-install screen with nothing naming the deploy as the cause.
  Bounded to one cadence, and partly mitigated for free — the plugins repopulate
  independently, so ADR-0089's chip fills in progressively and a half-populated
  roster reads as recovery in progress.
- **A zero-plugin environment gets its own state rather than a startup
  refusal.** Failing fast was tempting — it is ADR-0035's pattern — but ADR-0074
  makes *a rename is a delete plus a create*, so declaring an environment before
  wiring its plugins is a normal intermediate edit, and refusing to boot turns a
  half-finished config into an outage. Nothing is at risk here; the environment is
  merely empty. Folding it into "not read yet" was the one genuinely wrong option:
  it renders a config error as a transient wait, so the operator waits instead of
  fixing.
- **ADR-0018 is amended.** The MVP canvas scope gains three empty states where it
  listed none.
