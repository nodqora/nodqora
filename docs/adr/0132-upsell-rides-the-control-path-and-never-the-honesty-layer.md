# ADR-0132: Upsell rides the control path, and never the honesty layer

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [How Community surfaces gated features](https://github.com/fredskor/nodqora/issues/47)

## Context

[ADR-0131](0131-enterprise-is-named-only-where-its-free-half-is-in-hand.md)
places the mention beside the free capability it extends. Applied literally,
that collides with
[ADR-0122](0122-communitys-knowledge-of-enterprise-lives-in-the-shell.md)'s
shell-only rule, because the free half usually renders inside a component
Enterprise imports.

Checking each Enterprise row against where its free neighbour renders:

| Enterprise row | Free neighbour | Renders in |
|---|---|---|
| Audit aggregation | audit capture | config — **shell** |
| Group sync, view-scoping | authentication | session, settings — **shell** |
| The journal | drift against the previous fold | canvas marks — **shared** |
| Continuous monitoring | blast radius | drawer-initiated — **shared** |
| Incident timeline | the incident view | canvas and drawer — **shared** |

The two rows with clean shell homes are the two thinnest. The three that would
actually sell all hang off capabilities rendering where ADR-0122 forbids a word.

There is a second collision, and it is the one this ticket was told to check.
**The plugins chip is a control** — it opens a popover carrying every plugin's
outcome, reasons and cause — and continuous monitoring is precisely *"tell me
when this changes"*. So an adjacency rule permits a paid mention inside
[ADR-0081](0081-outcome-is-a-resting-chip-and-an-exception-surface.md)'s honesty
surface. ADR-0122 makes it worse by listing **empty states** as legal shell
placement, and
[ADR-0087](0087-the-canvas-has-three-empty-states.md)'s three states are honesty
statements.

## Decision

**The mention attaches to the control that would invoke the capability, never to
the rendering of its result. And the honesty layer is exempt by name, whatever
the control-path rule would otherwise allow.**

**1. Upsell rides the control path.** Drift, blast radius and incident mode each
need an entry control — a toggle, an action, a mode switch — and ADR-0122's own
list already puts controls in the shell: *"routes, navigation, settings and
empty states Community composes for itself"*. So a shell-owned `Compare` control
offers *previous fold* and names *any point in time* as an Enterprise option in
the same menu, while the canvas renders the result with no upsell anywhere in
it.

This gives the whole question a rule with no judgement left in it at the pixel
level: **the honesty layer reports what we could see; the control path offers
what you could do. Enterprise is named only in the second.** Banners, marks, the
chip and the empty states are all rendering, so a paid mention structurally
cannot compete with them.

It rhymes with the gating story on purpose.
[ADR-0120](0120-an-unlicensed-install-freezes-the-control-path.md) freezes an
unlicensed Enterprise install's **control path** while the data path stays
alive. Under this ADR the control path is also where a Community install names
what it does not have. One surface, one meaning, in both directions.

Rejected:

- **Silence where the free half renders in a shared component.** Honest and
  cheap, and it concedes the journal, alerting and the timeline — the three rows
  most likely to be paid for.
- **Promoting the capability**, so a Community feature whose Enterprise
  neighbour is worth naming gets composed by the shell rather than the canvas.
  Refused outright: it lets a proprietary edition dictate Community's component
  structure, which is the error
  [ADR-0117](0117-enterprise-builds-its-own-frontend.md) refused when it killed
  named slots.

**2. The honesty layer is carved out by name.** These carry no Enterprise
mention, ever, overriding the control-path rule where they collide:

- the **plugins chip** and its popover (ADR-0081, ADR-0089)
- the **banners** (ADR-0081, ADR-0088)
- the **outcome marks** on node cards — retained and blind (ADR-0083)
- the **three canvas empty states** (ADR-0087)

Enumerated rather than left as a principle, so the carve-out survives the
components being rewritten. ADR-0087 and ADR-0089 both exist because a principle
was not specific enough about a surface, and the enumeration costs four nouns.

The reason is [ADR-0026](0026-health-observation-declares-its-outcome.md)'s. ADR-0081's
chip exists so *"is everything actually being watched?"* is answerable on a good
day, and ADR-0026 accepted a narrow residual honesty gap **on the understanding
that the plugin status is shown** — and read. A reader must never learn *"we
could not look"* and *"you should pay us"* in the same glance, and a chip people
start reading as marketing is a chip that stops discharging ADR-0026's promise.

Monitoring is therefore named beside **blast radius**, where the reader is
asking a question of their own graph — never beside the chip that is telling
them the graph is incomplete.

## Consequences

- **Nothing is built now.** No shell control has a paid neighbour today, so the
  first application is whichever of drift, blast radius or incident mode lands
  first.
- **This constrains where those three features put their entry controls.** Drift
  and incident mode were always plausible shell controls; blast radius is the
  awkward one, since the natural gesture is inside the drawer on a selected
  node. If it ends up drawer-only it gets no mention, and that is the correct
  outcome rather than a reason to bend the rule — ADR-0117's cost note about
  reaching for slots at exactly the wrong moment applies here in miniature.
- **Monitoring beside the chip is the single most natural-looking upsell in the
  product**, which is why it is forbidden in writing rather than resisted each
  time it is proposed.
- **The carve-out list is a maintenance item.** A new honesty surface must be
  added to it, and nothing will remind anyone;
  [ADR-0134](0134-the-word-enterprise-has-one-address-in-the-frontend.md)'s
  check catches the leak but cannot know that a new component was supposed to be
  exempt.
- **Revisit trigger.** ADR-0134's boundary test failing twice on a *legitimate*
  need to name Enterprise inside a shared component. That is evidence the
  placement rule fights the product's structure, and per ADR-0117's precedent the
  answer would then be a narrower boundary rather than abandoning it.
