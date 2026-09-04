# ADR-0094: `?node=` resolves by the folded key, and a non-canonical hit rewrites the URL

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Frontend routing and deep-linking](https://github.com/fredskor/nodqora/issues/21)

## Context

ADR-0092 puts the node key in `?node=`. Resolution happens **entirely in the
frontend**: ADR-0053 left no per-node route, so the client matches the parameter
against the `/graph` document it already holds.

Two precedents point the same way. ADR-0020 stores keys verbatim but folds and
trims for uniqueness and merge, and ADR-0067 folds for search because *"a
case-sensitive search over a case-insensitive identity model would let a user type
a string that **is** the node key and get nothing."*

## Decision

**`?node=` resolves by ADR-0020's own comparison — trimmed and case-folded**
against the folded key.

**On a hit whose parameter differs from the stored key, the URL is rewritten to
the stored key via `replaceState`.**

## Consequences

- **Resolution is unambiguous by construction.** ADR-0020's
  `UNIQUE (environmentKey, lower(trim(key)))` guarantees at most one folded match
  per environment, so folding introduces no tie to break.
- **Exact matching was rejected because it would give one string two meanings.**
  A user pasting `Payments-API` finds the node through search (ADR-0067) and fails
  through a deep link — two rules for the same identity model, and the deep link
  is the half more likely to have arrived from a Kubernetes alert or a hand-typed
  message.
- **Canonicalizing is what keeps ADR-0095's retained parameter meaningful.** That
  ADR keeps an unresolved parameter in the URL so a later poll can resolve it; if
  the same node had many spellings, the retained string would be one of several
  and comparisons against it would be spelling-sensitive. One node, one URL.
- **`replaceState`, not push, deliberately.** ADR-0096 makes selection changes
  push; a canonicalization is not a selection change and must not appear in the
  Back trail as a second visit to the same node.
- The rewrite is the one place the app edits a URL the user supplied. It is
  confined to a spelling change on a resolved hit — a miss is left exactly as
  given, because there is no canonical form to rewrite it to.
