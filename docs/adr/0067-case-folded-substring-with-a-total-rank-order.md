# ADR-0067: Case-folded substring matching with a total rank order, and no fuzzy tier

- **Status**: Accepted
- **Date**: 2026-09-03
- **Ticket**: [Simple name search behaviour](https://github.com/fredskor/nodqora/issues/15)

## Context

The reference pipeline's own list of hard cases says of `payments-enricher` and
`payments-events-v1`: *"search must disambiguate, not just substring-match."*

That brief is wrong, and the correction is the decision. `payments-e` matches
both names, and **both are correct hits**. No matcher separates them, because
there is nothing to separate — the user has typed a prefix that two nodes
genuinely share. Disambiguation is not the matcher's job; it is the result
list's (ADR-0068). What the matcher owes is determinism and ordering, not fewer
results.

## Decision

**Case-folded substring matching.** No fuzzy or subsequence tier.

Results carry a **total rank order**: exact `key` → prefix of `key` or
`displayName` → substring of `key` or `displayName` → match on a backing
**segment** (ADR-0066) → match on a whole backing reference. Ties break
alphabetically by `key`.

## Consequences

- Case-folding follows ADR-0020, which already case-folds keys for uniqueness
  and merge. A case-sensitive search over a case-insensitive identity model
  would let a user type a string that *is* the node key and get nothing.
- The order must be **total**, not best-effort, for the reason ADR-0050 forced
  canonical collection ordering one layer down: an unstable order means the same
  query surfaces a different top hit on different renders.
- **ADR-0021's ban on a fuzzy tier does not transfer**, and this is a separate
  judgement rather than an inherited one. That ban protected identity
  *resolution*, where nothing human intervenes; here a person picks from the
  results, so a wrong candidate is discarded rather than merged. Fuzzy is
  declined on scale instead: subsequence matching is invisible across ten
  fixture nodes and returns near-everything for short queries on the
  two-hundred-connector cluster ADR-0036's whole-cluster scope permits.
- Because segments are searchable, prefix-anchoring was available and was still
  rejected — `enricher` would find `enricher-v2` via the segment, but `v2`
  would not find anything at all.
