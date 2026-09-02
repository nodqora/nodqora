# ADR-0060: Unversioned paths, no envelope, problem+json errors

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

Product plan §58 raises API and plugin stability as a concern, but aims it at
the plugin SDK and at post-MVP consumers. The MVP ships exactly one client, from
this repository, deployed alongside the backend.

## Decision

- **No version segment.** `/api/…`, not `/api/v1/…`.
- **No envelope.** Resources are returned directly; there is no
  `{ data, meta, errors }` wrapper.
- **Errors are RFC 9457 `application/problem+json`.**
- An unknown or unconfigured environment key is a plain **404**.

## Consequences

- A `/v1` that never gains a `/v2` is decoration, and it costs a path segment in
  every URL forever. The moment a second, independently deployed consumer exists
  is the moment to add versioning — and that moment is post-MVP by definition.
- Versioning now would also invite treating this shape as frozen while it is
  still being learned. The map's premise is that it is being discovered.
- An envelope was rejected because the payloads already carry their own
  `plugins[]` outcome block (ADR-0056) and `observedAt`; the envelope would be a
  second, emptier home for the same kind of thing.
- Environment keys match exactly while node keys are case-folded (ADR-0052).
  The asymmetry is deliberate: environment keys are operator-authored config,
  node keys are minted independently by four plugins and must collide on purpose.
