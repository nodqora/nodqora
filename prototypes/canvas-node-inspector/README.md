# PROTOTYPE — canvas and node inspector

**Throwaway.** Built to answer
[issue #7](https://github.com/fredskor/nodqora/issues/7) and kept only as the
primary source behind ADR-0016 through ADR-0019. Not production code: no tests,
no error handling, no build step, and the fixture is hardcoded.

This branch exists so the *losing* variants survive the decision. `main` keeps
only the ADRs.

## Run it

Open `canvas-prototype.html` in a browser. That's it — it's self-contained apart
from three pinned CDN scripts (React 18, ReactDOM 18, `@xyflow/react` 12.11.6).

Published for review at
<https://claude.ai/code/artifact/e532b41f-20dc-4845-9379-65c9ccda6d2c>.

## The three variants

Switch with the bottom bar, the `←` / `→` keys, or `?variant=A|B|C`.

| | organising axis | layout | health | metrics | inspector |
|---|---|---|---|---|---|
| **A — Flowline** | flow | left-to-right | colour rail + glyph + chip | opt-in toggle | right drawer |
| **B — Lanes** | ownership | LR inside team swimlanes | 5px left bar | swaps chip subtitle | bottom panel, 3 columns |
| **C — Console** | signal | top-down | tinted node fill | always on | popover anchored on canvas |

**A won** (ADR-0016), taking B's declared-node badge and its cross-team boundary
idea as an edge treatment. C's always-on raw signal lost as a canvas default but
survives inside the inspector, where raw signal is always shown (ADR-0019).

## Files

- `canvas-prototype.html` — the runnable, self-contained page
- `canvas-prototype.src.html` — same file with `/*__XYFLOW_CSS__*/` in place of
  the inlined vendor stylesheet

The built file is the source with `@xyflow/react@12.11.6/dist/style.css`
substituted in, because the review host blocks external stylesheets. XYFlow's
UMD build expects a `jsxRuntime` global that React's UMD does not ship, so the
page shims it onto `React.createElement` before loading XYFlow.

## Worth knowing if you rebuild this

XYFlow writes measured node dimensions back through `onNodesChange`. Rebuilding
the node array on every state change throws them away, and the minimap silently
renders nothing because it only draws nodes that have them. The prototype
carries `measured` across rebuilds by id.
