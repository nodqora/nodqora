# Prototype — surfacing plugin outcome and node freshness

Throwaway. Answers [issue #17](https://github.com/fredskor/nodqora/issues/17) only.
Published at https://claude.ai/code/artifact/482faacc-01b3-4632-bb94-e54a5a99c525

The canvas and inspector are held **fixed** at what #7 decided (ADR-0016–0019).
The only thing that varies between variants is where ADR-0026's `outcome` and
ADR-0056's per-source `confirmedAt` render.

## Running it

Open `outcome-freshness.html` in a browser. No build, no server. React 18 and
XYFlow 12 load from CDN; the XYFlow stylesheet is inlined because the artifact
CSP admits no third-party stylesheets.

`outcome-freshness.src.html` is the hand-written half — data, components, app
shell. The published file is that concatenated after the font links, XYFlow's
stylesheet and the prototype's own CSS.

## Variants

Switch with the bottom bar, `←` / `→`, or `?variant=A|B|C|D`. **D is the
default and the one that won.**

| | stance |
|---|---|
| **A — Quiet rail** | outcome is a property of the *environment*; the canvas is untouched |
| **B — Exception mark** | nothing renders until a poll comes back non-`COMPLETE` |
| **C — Second channel** | "how well we looked" is a permanent peer of "what we found", everywhere |
| **D — Merged** | B's exception marks plus A's resting chip. **Fixed as ADR-0081–0084.** |

## Fixtures

On the `Fixture` switcher, over the reference pipeline in `production`:

- **`baseline`** — all four plugins `COMPLETE`. The case that must not cry wolf:
  four of ten nodes are permanently `UNKNOWN` with everything perfectly healthy.
- **`retained`** — `connect` discovery `PARTIAL` for 23 minutes. Health is
  untouched, so **nothing on the health channel changes at all** and freshness is
  the only signal there is.
- **`incident`** — every plugin `COMPLETE`; both connectors genuinely `DISABLED`.
- **`blind`** — ADR-0026's worked example. `connect` unreachable, both connectors
  compose down to `kubernetes` + `kafka` and read `HEALTHY`; `payments-enricher`
  is honestly `DISABLED`. Flip between `incident` and `blind` for the
  side-by-side that ADR-0083 exists to resolve.

## What it settled

- **A resting chip plus exception surfaces** beat both environment-only and an
  always-on channel (ADR-0081).
- **Outcome gets no hue** — a monochrome square family, because ADR-0017 has
  already spent all five colours *and* all five shapes (ADR-0082).
- **Retained and blind are different problems** and get different marks; the
  health caveat sits inside Health rather than in a section of its own
  (ADR-0083).
- **Retention is a comparison, not a timeout.** The prototype originally carried
  an invented `2 × discovery interval` constant. Writing it up killed it:
  `/api/meta` publishes only the *minimum* cadence (ADR-0059), so a per-plugin
  threshold was never available without new API surface — and it was never
  needed, because `confirmedAt < recordedAt` is exact (ADR-0084). The rule in
  this code is the corrected one; no pixel changed.
