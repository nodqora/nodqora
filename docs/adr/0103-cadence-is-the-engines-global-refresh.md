# ADR-0103: Cadence is the engine's global refresh; the per-plugin interval and timeout keys are not bound

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Slice 2 — `kubernetes` discovery: identity resolution and the merge](https://github.com/fredskor/nodqora/issues/23)
- **Amends**: ADR-0035

## Context

ADR-0012 established two cadence loops, *"each with a default interval and per-plugin
overrides"*, and handed the numbers to the plugin tickets. ADR-0035 then wrote a per-plugin block
for `kubernetes`:

```yaml
discoveryInterval: 5m
healthInterval: 30s
discoveryTimeout: 30s
healthTimeout: 10s
```

ADR-0042 wrote the same four keys for `kafka` and `connect`.

Slice 1 built the loops without them. `nodqora.refresh.discovery` and `nodqora.refresh.health` are
file-declared and global, and `DiscoveryEngine` derives its timeout as half the interval. No
per-plugin override machinery exists anywhere in the core.

Slice 2 is the first slice to bind a plugin config type whose ADR wrote those keys, which forces
the question: bind four keys nothing reads, or record that the mechanism is unbuilt.

## Decision

**Cadence is `nodqora.refresh`, applied to every plugin. A plugin's config type binds no cadence
key, and the timeout is derived rather than configured.**

The numbers ADR-0035 chose are unchanged — they *are* the engine's defaults — and its constraint
that a timeout sit strictly below its interval is satisfied more strongly by derivation than by
validation: a derived half-interval cannot be misconfigured, so there is no validation rule to
forget.

**Per-plugin overrides return when a plugin needs a cadence another plugin does not**, which is
ADR-0012's provision reopening on a real forcing function rather than on a config block written
before any of it ran.

## Consequences

- **This is an ADR-0102 amendment of mechanism, and ADR-0035's reasons are untouched.** Its
  argument is about *5 minutes and 30 seconds* — that `updatedAt` means "topology changed" so
  churning discovery faster buys nothing, and that an SRE watching a rollout wants readiness inside
  a minute. Both survive intact; only the per-plugin key does not exist.
- **ADR-0042 inherits this** rather than reopening it in slice 4. Its numbers are ADR-0035's
  numbers, so there is nothing a per-plugin key would currently express.
- **Cost: two plugins wanting different cadences cannot have them.** Nothing in the fixture wants
  it, and the first thing that does is a clean trigger to build the overrides ADR-0012 already
  licensed. A plugin holding an interval nothing reads would be worse — configuration that
  silently does nothing is a lie the file tells the operator.
- **Cost: a slow plugin gets half the global interval as its timeout** — two and a half minutes at
  the 5m discovery cadence, where ADR-0035 wrote 30s. That is more generous than intended, not less,
  and a plugin that hangs still cannot overlap its own next run, which is the property the timeout
  exists for.
- The health half arrives with slice 3 on the same rule, so `healthInterval` and `healthTimeout` do
  not reappear as a plugin key either.
