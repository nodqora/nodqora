# ADR-0133: The mention is an inert label, one factual line and one link

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [How Community surfaces gated features](https://github.com/fredskor/nodqora/issues/47)

## Context

[ADR-0131](0131-enterprise-is-named-only-where-its-free-half-is-in-hand.md) and
[ADR-0132](0132-upsell-rides-the-control-path-and-never-the-honesty-layer.md)
settle which capabilities are named and where. What the mention actually *is* —
and what it sounds like — is the remainder.

The house voice is already established by the empty states and the inspector:
flat declarative statements. *"No plugins are configured for `production`."*
*"No owner recorded. Nobody to page."* No exclamation, no second-person
imperative, no verbs like *unlock*.

## Decision

**An inert item in the control's own menu, carrying a label, one factual line,
and one link to a single URL held in one constant.** Taking a shell-owned
`Compare` control as the worked example:

```
Compare ▾
  Previous fold
  Any point in time — Enterprise          ← inert
    Enterprise keeps topology history. nodqora.io/editions
```

**Tone.** The house voice, unchanged. The line states what the capability is, in
the present tense, and stops. It does not address the reader, does not describe
a benefit, and does not ask for anything. Because
[ADR-0131](0131-enterprise-is-named-only-where-its-free-half-is-in-hand.md)
guarantees the reader is holding the free half at the moment they read it, the
copy has nothing to persuade anyone of — the adjacency has already made the
argument.

Rejected:

- **Label only** — `Any point in time — Enterprise` and nothing else. It fails
  its own purpose: it tells the reader something exists and gives them no way to
  find out what it is. That is the *"discoverable only by reading marketing"*
  problem
  [ADR-0122](0122-communitys-knowledge-of-enterprise-lives-in-the-shell.md)
  refused, relocated inside the product rather than solved.
- **Label plus line, no link.** Informs, then dead-ends. A reader who wants the
  capability has to leave and guess a search term, and a Community user who
  never learns Enterprise exists never buys it — which is the entire reason
  ADR-0122 declined to keep the product silent.
- **An explanatory drawer.** A panel describing the feature is a marketing
  surface with a scroll bar, and it competes with the inspector for the one
  drawer position the layout has (ADR-0019).

## Consequences

- **The link does not resolve on an air-gapped install.**
  [ADR-0121](0121-the-key-verifies-offline-at-boot-and-daily.md) protects
  exactly that case for the key, and this does not extend the protection.
  Accepted rather than designed around: a URL rendered as text is still the
  information, and the alternative — bundling docs into the Community artifact
  — invents a packaging story for the sake of an upsell, when Community has no
  production packaging at all yet
  ([ADR-0117](0117-enterprise-builds-its-own-frontend.md)).
- **One constant, one place to go stale.** The URL is held once, so a change of
  domain or path is a one-line edit rather than a search.
- **The item is inert, so it needs no disabled-state design.** There is no
  click, no hover card, no modal, and nothing to do when a reader interacts with
  it — which keeps the control's own affordances honest, since everything else
  in that menu does something.
- **Nothing here is tested against a reader.** The tone is asserted from the
  product's existing voice, not validated, and the first mention ships in
  whatever feature fires ADR-0132's rule. That feature's own review is the first
  real check on whether this reads as help or as an advert.
