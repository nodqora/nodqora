# ADR-0160: The theme is a three-state choice, and the mark is one file

- **Status**: Accepted
- **Date**: 2026-09-12
- **Ticket**: [The canvas has one theme, and the product has no mark on it](https://github.com/nodqora/nodqora/issues/92)
- **Amends**: [ADR-0017](0017-health-encoded-by-shape-and-colour.md) and [ADR-0082](0082-outcome-is-encoded-without-hue.md) — both keep their encodings; each now has two palettes rather than one.

## Context

`styles.css` declared one palette and read no media query, so the product was
light-only on a screen people leave open all day. nodqora.com already carried a
full dark block, which made the vendor site and the product two different
objects to look at.

The product also had no mark: the shell's brand was the string `Nodqora` and
`index.html` declared no icon, so a tab full of dashboards showed this one as a
blank page.

## Decision

**The theme is a choice of three — `system`, `light`, `dark` — defaulting to
`system`, persisted in `localStorage` under `nodqora.theme`.**

`system` is a *state*, not the absence of one. A reader who has never touched the
control and a reader who deliberately pinned Dark are answering different
questions, and collapsing them loses the first reader's OS preference the moment
they experiment with the second. It is also the only choice that keeps tracking:
a laptop that flips at sunset flips the canvas, which is what the vendor site
already does.

**Nothing in TypeScript resolves `system` to a theme.** The stylesheet resolves
it with `prefers-color-scheme` and XYFlow resolves it inside `colorMode`, both
without JavaScript and both before the first paint. A third resolution would be
a copy of a rule already living in two places that cannot drift, and it could
only be consulted after mount — which is exactly when a theme is too late. The
`theme` module owns the *choice*: reading it, storing it, and saying what the
document should carry because of it.

**`system` therefore stamps nothing on `<html>`; a pin stamps
`data-theme`.** The cascade is:

```css
:root { /* light */ }
@media (prefers-color-scheme: dark) { :root:not([data-theme='light']) { /* dark */ } }
:root[data-theme='dark'] { /* dark */ }
```

An unstamped root is how a cold load is already correct — no inline script, and
nothing to flash. This matters beyond tidiness: the vendor site's CSP forbids
`unsafe-inline`, and the classic anti-flash trick is an inline script. A design
that does not need one cannot be broken by adding the header here later.

**The five health hues are re-picked for the dark ground, not reused.** A hue
chosen to carry against white is not the same hue against `#071322`. Holding
them fixed would quietly undo half of ADR-0017 — the half arguing the palette is
legible *before* you reach for the glyph. The glyph stays the primary encoding
and still does the work if colour fails; re-picking keeps it from failing.
`UNKNOWN` stays the quietest of the five against its own ground, because *calm,
not alarming* is a statement about contrast rather than about a hex value.

**ADR-0082's pair is inverted, not coloured.** On this ground the "ink" is the
light value and the tinted ground is the lifted one. Outcome stays monochrome
and stays a square.

**The mark is one file, `mark.png`, used in both themes.** Its counters are
transparent rather than white, so the bar's own background shows through them.
There is no second asset and no per-theme swap.

## Consequences

- **The dark block is written twice** — once under the media query, once under
  the pin — because CSS cannot share one declaration block across both. The
  duplication is real and is accepted; the alternative was resolving the theme in
  JavaScript, which costs the flash-free first paint above.
- **The palette now has two halves that must move together.** A new token added
  to one and forgotten in the other inherits the light value on a dark ground,
  which is the failure this file makes easy to spot and nothing currently
  catches. No test asserts contrast.
- **`colorMode` is threaded to `Canvas`.** XYFlow owns the background dots, the
  controls and the edge defaults, and its `ColorMode` is the same three values,
  so the choice is handed over whole.
- **Two canvas tests pin `light`.** jsdom implements no `matchMedia`, so
  `system` is not a value they can be given; the theme is not what either test is
  about.
- **The brand assets arrive from a second repository** and are now duplicated in
  two trees, with no build step joining them. A change to the logo kit has to be
  copied here deliberately. That is the price of the vendor site having no
  publish step worth consuming.
- **The mark is a trademark in an Apache-2.0 tree.** Apache-2.0 §6 grants no
  trademark rights, and ADR-0130 states the licence once per repo — so the logo
  files sit under a licence statement that was written for code. Whether `NOTICE`
  should reserve the marks explicitly is **not settled here**; it is a licensing
  decision and belongs in its own ADR.
