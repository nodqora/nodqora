# ADR-0134: The word "Enterprise" has one address in the frontend

- **Status**: Accepted
- **Date**: 2026-09-07
- **Ticket**: [How Community surfaces gated features](https://github.com/fredskor/nodqora/issues/47)

## Context

[ADR-0122](0122-communitys-knowledge-of-enterprise-lives-in-the-shell.md)
recorded exactly one cost it could not pay: *"The rule is a placement rule and
nothing enforces it. ADR-0115's ArchUnit check covers `nodqora-core/src/main`
and no TypeScript at all. A shared component that grows an upsell string is
caught by review, or by an Enterprise customer."*

That precedent is not tooling. ADR-0015's boundary is a plain JUnit test —
`nodqora-core/src/test/java/io/nodqora/core/CoreKnowsNoPluginTest.java` — and
the frontend already runs vitest across thirteen test files. The equivalent
costs one file.

## Decision

**All Enterprise naming lives in `frontend/src/editions/`, and a test asserts it
appears nowhere else under `frontend/src`.**

The module holds the labels, the one-line descriptions
([ADR-0133](0133-the-mention-is-an-inert-label-one-line-and-one-link.md)) and
the URL constant. Everything else in the frontend imports from it or says
nothing. The test scans `frontend/src` for the identifiers `Enterprise` and
`enterprise` and fails on any occurrence outside `frontend/src/editions/`,
allowing only the shell's import of it.

This is strictly stronger than ADR-0122's own formulation. *"Not inside
components Enterprise imports"* requires knowing which components Enterprise
imports — a list that lives in another repository and will change. *"One legal
address"* catches the leak in a component nobody has written yet, and needs no
knowledge of the Enterprise build at all.

Rejected:

- **The module without the check** — ADR-0122's status quo with a tidier home.
  It leaves the recorded cost unpaid for the sake of not writing one file.
- **Strings inline at each control.** Nothing to audit when
  [ADR-0128](0128-community-capabilities-move-one-way-only.md) moves a row
  free-ward and a line of copy becomes false, and nothing for the check to
  target.

## Consequences

- **Nothing runs this on push.** The repo has no CI — that is the map's own
  out-of-scope entry, and ADR-0129 and ADR-0130 left their policies in the same
  unverified state. The check is real, local, and as unenforced as the DCO. It
  is still better than review, because it fails loudly for anyone who runs the
  suite, and it gives the founding-CI decision one more concrete thing to gate.
- **The Enterprise repo gets a checkable fact.** It imports `canvas` and
  `inspector` and never `editions`, so the components it pulls in under
  [ADR-0117](0117-enterprise-builds-its-own-frontend.md) are provably free of
  advertising for what its customers have already bought — the leak ADR-0122
  called the sharper problem.
- **Staleness becomes a one-file audit.** When a capability moves free-ward
  under ADR-0128, the copy that has to change is all in one directory.
- **The check is lexical, so it is both over- and under-tight.** It will fail on
  a comment or a test fixture that happens to use the word, which is a small tax
  paid in renaming; and it cannot catch an upsell written without the word —
  *"available in the paid edition"* passes. It enforces the address, not the
  intent, and
  [ADR-0132](0132-upsell-rides-the-control-path-and-never-the-honesty-layer.md)'s
  carve-out list still relies on review.
- **`frontend/src/editions/` does not exist yet** and should be created by the
  first feature that fires ADR-0132's rule, together with the test. Writing the
  test against an empty module today would assert a rule about a surface with no
  content, and the map is charting decisions rather than building them.
