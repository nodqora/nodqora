# ADR-0169: Enterprise opens on a floor and evidence, and the clock forces a decision

- **Status**: Accepted
- **Date**: 2026-09-15
- **Ticket**: [What must be true of Community before Enterprise opens](https://github.com/nodqora/nodqora/issues/108)

## Context

ADR-0114 through ADR-0128 design Enterprise in detail: the second assembly, the
key, the unit, the trial, the price. None of them says **when building it
starts**. ADR-0153 fixes what `1.0` means mechanically (the libraries public, the
config grammar settled) and says nothing about product completeness. Phases 5–10
of the plan are Community by ADR-0108's default, all unbuilt, and none has a
stated relationship to starting Enterprise.

Left alone, Enterprise opens because the last Community ticket closed. Three
facts make that expensive:

- **The second assembly costs before it delivers.** A private repo, a build,
  published-artifact consumption and key handling (ADR-0114, ADR-0121), all
  before anyone can buy anything.
- **Everything that demos well is free** (ADR-0107). The paid half is
  administration, memory and vigilance, which sells to an installed base, not
  to a pitch.
- **Community capabilities move one way** (ADR-0128), and there is no free
  Enterprise tier to seed adoption with (ADR-0126).

One more fact constrains what evidence can be: **Nodqora cannot count its
installs.** No ADR adds telemetry, and ADR-0121 refuses a key that phones home as
the first step into it. Evidence has to be visible from outside.

And nothing in the tree authenticates anyone. The first Enterprise rows,
group synchronisation and view-scoping, act on an authenticated user
(ADR-0109).

## Decision

**Enterprise opens when a floor and a gate are both met, in either order.**

### What "opens" means

**Enterprise opens at the first commit to the private repository** — ADR-0114's
second assembly. Before it, ADRs about Enterprise, edits to the ledger and
answering an enquiry are all allowed. A trial is not, by construction: ADR-0125
makes a trial a key to a private artifact that does not yet exist.

### The floor

1. **This map's tag** — the Community release in which Prometheus closes Tier 1.
2. **Community authentication** — OIDC or SAML against the user's own identity
   provider, shipped in a tagged release.

Nothing else. `1.0` is a promise to self-hosters, and Enterprise consumes the
libraries privately (ADR-0141). Phases 5–10 are how Community earns evidence,
not what Enterprise builds on.

### The gate

Either of:

- **One enquiry** from an organisation running Community, for a capability the
  ledger already places in Enterprise. That is ADR-0107's buyer, and one is
  enough.
- **Issues from three distinct outside organisations** plainly running Community
  against a real estate. An installed base, even one that has not yet asked to
  pay.

**Stars and image pulls never count.** They are cheap, CI and mirrors produce
them, and a bar met by promotion is not a decision.

An enquiry for something Community already ships does not open the gate. It is
ADR-0107's revisit trigger, and it reopens the ledger instead.

### The clock

**Six months from this map's tag**, matching ADR-0107's window so one clock
serves both. When it runs out, **nothing opens automatically**. An ADR must
choose:

- **open Enterprise anyway**, saying why no evidence was acceptable, or
- **reopen the ledger** under ADR-0107, which starts a fresh six-month clock.

The clock repeats until Enterprise opens. It is never cleared by lapsing.

### Opening is always written down

Whichever route opens it, **an ADR records it**, linking the enquiry, the three
issues, or the fallback decision.

Rejected:

- **A capability bar alone.** Always satisfiable by building more, which is
  the drift this ADR exists to stop.
- **An evidence bar alone.** With one maintainer and no telemetry, evidence may
  never arrive, and the wait would go on unrecorded.
- **The clock opening Enterprise when it runs out.** It would start the paid
  half at the moment ADR-0107 says the paid half may be drawn wrong.
- **`1.0` on the floor.** It gates Enterprise on a readiness Enterprise does not
  consume.

## Consequences

- **Authentication is the next Community effort** after this map, though no
  roadmap phase leads with it.
- **Opening Enterprise becomes an act with a citation.** Anyone reading the
  private repo's first commit can find the ADR that allowed it.
- **The judgement is still a maintainer's.** "Plainly running against a real
  estate" and "an organisation" are read by a person, not a script; the ADR
  that opens Enterprise is where that reading is shown.
- **Enterprise may never open.** A clock that repeats while the ledger keeps
  being reopened is a legitimate outcome, and each lap is on the record.
- **Revisit trigger.** A second assembly becoming cheap enough that its cost no
  longer argues for waiting — for example the private repo already existing for
  another reason — reopens the floor and the gate together.
