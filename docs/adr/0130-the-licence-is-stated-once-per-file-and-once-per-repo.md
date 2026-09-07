# ADR-0130: The licence is stated once per file and once per repo

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Add LICENSE, NOTICE and the contribution agreement to the repo](https://github.com/fredskor/nodqora/issues/46)

## Context

[ADR-0129](0129-inbound-is-dco-because-apache-2-0-already-grants-the-sublicense.md)
settles what the licence *is* and how it arrives. Two mechanical questions
remain, and both are easy to get wrong in a way that is expensive to undo: what
a source file says about its own terms, and what `NOTICE` contains.

No file in the tree carries a copyright or SPDX header today — 141 Java files,
40 TypeScript files, 11 Kotlin build scripts.

`NOTICE` deserves more care than it usually gets, because Apache-2.0 §4(d)
makes its contents **propagate**: any derivative work anyone distributes must
carry a readable copy of the attribution notices it contains. Creating one is a
permanent obligation imposed on every downstream redistributor — including
forks, and including the Enterprise assembly
([ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md)), which will
bundle Community jars in its fat jar and so redistributes under §4. It is also
the only attribution leverage a permissive licence offers, which matters while
trademark and naming remain unspecified on the map.

## Decision

**Every source file carries a one-line SPDX tag and no copyright line.
Copyright is stated exactly twice — in `LICENSE` and in `NOTICE` — and `NOTICE`
carries the project's own attribution and nothing else.**

Three parts:

1. **`// SPDX-License-Identifier: Apache-2.0`** as the first line of every
   source file, before the package declaration or first import, in the file's
   own comment syntax. Applied to all 192 existing files.
2. **No per-file copyright line.** The holder is named once in `LICENSE` and
   once in `NOTICE`, so there is nothing to keep in sync when years pass or the
   holder changes — which under ADR-0129's collective holder is a live
   possibility rather than a hypothetical.
3. **`NOTICE` is the product name, the copyright, and one attribution
   sentence.** It is explicitly *not* a dependency inventory.

The tag earns its line twice over. Nodqora sells to enterprises, whose
procurement runs SCA scanners that read exactly this field, and a headerless
file is a finding in several of them. And
[ADR-0117](0117-enterprise-builds-its-own-frontend.md) has Community publishing
an npm package for Enterprise to build against — npm packages get vendored and
copied far more casually than jars, and the tag is what makes a file's terms
self-evident once it has left this repository.

Rejected, and why:

- **No headers at all.** Legally sufficient — Apache-2.0 does not require
  per-file notices, and the root `LICENSE` covers the tree. Declined for the
  two reasons above; the cost of the alternative is one line per file.
- **The full Apache boilerplate block** from the licence appendix. Around 2,100
  lines of comment across the tree, and a copyright line in 192 places that
  must all change when the holder does. It buys nothing over the SPDX tag that
  either a scanner or a human actually reads.
- **A third-party inventory in `NOTICE`.** The standard mistake. It drifts the
  moment a dependency changes, and §4(d) then obliges every downstream
  redistributor to carry the stale list forever. Dependency attribution is a
  distribution-time concern — a generated `THIRD-PARTY` artifact in the built
  distribution — and belongs to
  [artifact publishing](https://github.com/fredskor/nodqora/issues/52), not to
  the source tree.
- **Omitting `NOTICE` entirely.** Permitted; Apache-2.0 requires only that an
  existing one propagate. Declined because it discards the one attribution hook
  the licence provides, at no saving.

## Consequences

- **Every fork and every redistributor must carry Nodqora's attribution.** That
  is modest but real leverage, and it is in hand before the map's trademark and
  naming question is even sharp enough to ticket.
- **The Enterprise build inherits a §4 obligation.** Bundling Community jars
  means shipping `LICENSE` and `NOTICE` in the Enterprise distribution. It is
  one file copy, and it is a constraint the Enterprise repo starts with rather
  than discovers.
- **The header policy will decay.** Nothing verifies it — this repository has
  no CI, and founding CI is a decision with its own shape that a licensing task
  should not settle by side effect. A new file will eventually land untagged.
  Accepted knowingly; the fix is a `check` task or a CI step whenever CI
  arrives, and retrofitting one file is trivial where retrofitting 192 was the
  work done here.
- **The 192-file diff is mechanical and large**, and lands on `main` beneath
  every open documentation branch. It touches no behaviour and no test.
- **Revisit trigger.** CI arriving in this repository, at which point the
  header policy should gain a check and stop relying on discipline.
