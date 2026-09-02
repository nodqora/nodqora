# ADR-0058: The API returns nulls unresolved

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

ADR-0032 has the `kubernetes` plugin emit `displayName: null` on every node it
discovers — deliberately, to avoid manufacturing a merge conflict with YAML.
Under ADR-0012 and ADR-0043, `null` means **no opinion** and never has to mean
anything else. The canvas nevertheless has to draw something.

ADR-0048 already fixed the neighbouring case: a dangling `ownerKey` is
**ordinary**, and renders as a name with an empty contact section.

## Decision

The API never fabricates a value that no plugin supplied.

- `displayName` goes over the wire exactly as folded, `null` included. The
  **frontend** renders `displayName ?? key`.
- An unresolved `ownerKey` is returned as the bare key, with no Owner in
  `owners[]` and **no synthesized stub**.

## Consequences

- **The fixture supplies the deciding fact.** `payments-api`'s real,
  YAML-supplied `displayName` *is* the string `payments-api`. A server-side
  fallback makes that permanently indistinguishable from a node nobody has ever
  named — and "has anyone named this?" is a question the inspector, name search
  ([#15](https://github.com/fredskor/nodqora/issues/15)) and any future naming
  nudge all want to ask.
- `null` keeps meaning one thing everywhere in the system. The API does not
  become the single place where a `null` is invented over.
- A second `label` convenience field beside a nullable `displayName` was
  rejected: it keeps both signals but leaves every future consumer a standing
  question about which of two name-ish fields it wanted.
- The cost is one `?? key` in the frontend, in the two places that draw a name.
- ADR-0048's stub node is unaffected: a stub is materialized by the **fold**
  because a canvas edge needs two nodes. That is a model decision, not an API
  one, and it is not fabrication — it is the empty case of a node the model
  already has.
